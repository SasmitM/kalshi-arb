package com.kalshiarb.book;

import com.kalshiarb.model.FixedPoint;
import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.Side;
import java.util.NavigableMap;

/**
 * Applies one signed size change to one side of a book.
 *
 * <p>Isolated into its own class because it holds the three rules where a book silently rots,
 * and each deserves to be testable on its own:
 *
 * <ol>
 *   <li><b>A delta is a change, not a new absolute value.</b> It is added to whatever is already
 *       resting at that price. Treating it as an assignment produces a book that tracks reality
 *       for a while and then diverges without any error.
 *   <li><b>A level reaching zero is removed, not kept as a zero entry.</b> A retained zero level
 *       would still be the "best bid" as far as {@code lastEntry()} is concerned, so the book
 *       would quote a price at which nothing is available.
 *   <li><b>A level going negative throws.</b> Never clamp. A negative level means a delta was
 *       double-applied, applied out of order, or applied across a sequence gap — all of which
 *       are bugs in our own state. Clamping to zero hides the bug and leaves a book that is
 *       merely wrong, which the detector would then read as a constraint violation.
 * </ol>
 */
public final class DeltaApplier {

    private DeltaApplier() {
    }

    /**
     * Adds {@code deltaMicroContracts} to the level at {@code priceMicros}.
     *
     * @param side           the map for one side, price micros to size in micro-contracts
     * @param priceMicros    the level being changed
     * @param deltaMicroContracts signed change; negative is a cancel, fill, or expiry
     * @param ticker         only for the error message
     * @param bookSide       only for the error message
     * @return the new size at that level, zero if the level was removed
     * @throws NegativeLevelException if the level would go below zero
     */
    public static long apply(
            NavigableMap<Long, Long> side,
            long priceMicros,
            long deltaMicroContracts,
            MarketTicker ticker,
            Side bookSide) {

        long existing = side.getOrDefault(priceMicros, 0L);
        long updated = existing + deltaMicroContracts;

        if (updated < 0) {
            throw new NegativeLevelException(ticker, bookSide, priceMicros, existing,
                    deltaMicroContracts);
        }
        if (updated == 0) {
            // Remove rather than store zero: a zero level would still win lastEntry().
            side.remove(priceMicros);
            return 0;
        }
        side.put(priceMicros, updated);
        return updated;
    }

    /**
     * Thrown when a delta would push a level negative.
     *
     * <p>A distinct type so a replay harness can count these separately from other failures —
     * they are the signature of a sequencing bug rather than a decode bug.
     */
    public static final class NegativeLevelException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final transient MarketTicker ticker;
        private final Side side;
        private final long priceMicros;
        private final long existingMicroContracts;
        private final long deltaMicroContracts;

        NegativeLevelException(MarketTicker ticker, Side side, long priceMicros,
                long existing, long delta) {
            super("negative book level on " + ticker + " " + side + " at $"
                    + FixedPoint.format(priceMicros, 4) + ": "
                    + FixedPoint.format(existing, 2) + " + "
                    + FixedPoint.format(delta, 2) + " = "
                    + FixedPoint.format(existing + delta, 2)
                    + ". This is a sequencing bug, not a market condition; never clamp it.");
            this.ticker = ticker;
            this.side = side;
            this.priceMicros = priceMicros;
            this.existingMicroContracts = existing;
            this.deltaMicroContracts = delta;
        }

        public MarketTicker ticker() {
            return ticker;
        }

        public Side side() {
            return side;
        }

        public long priceMicros() {
            return priceMicros;
        }

        public long existingMicroContracts() {
            return existingMicroContracts;
        }

        public long deltaMicroContracts() {
            return deltaMicroContracts;
        }
    }
}
