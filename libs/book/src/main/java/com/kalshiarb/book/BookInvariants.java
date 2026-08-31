package com.kalshiarb.book;

import com.kalshiarb.model.FixedPoint;
import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.Price;
import com.kalshiarb.model.Side;
import java.util.Map;
import java.util.Optional;

/**
 * Structural checks that must hold after every update.
 *
 * <p>The headline one is the crossed-book invariant:
 * <b>{@code best_yes_bid + best_no_bid <= $1.00}, always.</b>
 *
 * <p>A sum above $1.00 cannot exist on a live feed. If someone bid $0.60 for YES while someone
 * else bid $0.45 for NO, the matching engine would already have paired them and minted a contract
 * pair for $1.00, pocketing the 5c. So observing a crossed book never means an opportunity — it
 * means our own reconstruction is wrong, from a mis-applied delta, a double-applied delta, or a
 * sequence gap that was not recovered. Trading on it would be trading on our own bug.
 *
 * <p>This is also the structural reason the project targets cross-market constraints at all:
 * intra-market YES/NO arbitrage is impossible by construction, so any edge has to come from
 * relationships <em>between</em> markets.
 *
 * <p>Exactly $1.00 is legal and merely means a zero spread — the two sides touch.
 */
public final class BookInvariants {

    private BookInvariants() {
    }

    /**
     * Checks every invariant, throwing on the first violation.
     *
     * <p>Cheap enough to run after each update: two {@code lastEntry()} lookups plus a scan of
     * the ladders, against a network round trip of tens of milliseconds.
     *
     * @throws CrossedBookException if the two best bids sum above $1.00
     * @throws IllegalStateException if a level is non-positive or a price is out of range
     */
    public static void check(Book book) {
        checkNotCrossed(book);
        checkLadder(book, Side.YES);
        checkLadder(book, Side.NO);
    }

    /** The crossed-book invariant on its own. */
    public static void checkNotCrossed(Book book) {
        Optional<Price> yes = book.bestBid(Side.YES);
        Optional<Price> no = book.bestBid(Side.NO);
        if (yes.isEmpty() || no.isEmpty()) {
            // One-sided books are normal: illiquid tail brackets genuinely have no bids on one
            // side, so the invariant is vacuous rather than violated.
            return;
        }
        // Summed in raw micros on purpose. Price is bounded to $1.00, so Price.plus would throw
        // a generic range error and lose the diagnostic this exception exists to give.
        long sum = yes.get().micros() + no.get().micros();
        if (sum > FixedPoint.ONE) {
            throw new CrossedBookException(book.ticker(), yes.get(), no.get());
        }
    }

    /** True when the invariant holds, for callers that want to count rather than throw. */
    public static boolean isCrossed(Book book) {
        Optional<Price> yes = book.bestBid(Side.YES);
        Optional<Price> no = book.bestBid(Side.NO);
        if (yes.isEmpty() || no.isEmpty()) {
            return false;
        }
        return yes.get().micros() + no.get().micros() > FixedPoint.ONE;
    }

    /**
     * Verifies no ladder holds a zero or negative size, and no price is out of range.
     *
     * <p>A zero level is a correctness problem rather than untidiness: it would still be returned
     * by {@code lastEntry()}, so the book would advertise a best bid at which nothing is
     * available, and a depth walk would consume a level of nothing.
     */
    private static void checkLadder(Book book, Side side) {
        MarketTicker ticker = book.ticker();
        for (Map.Entry<Long, Long> e : book.ladder(side).entrySet()) {
            long priceMicros = e.getKey();
            long size = e.getValue();
            if (size <= 0) {
                throw new IllegalStateException(
                        "non-positive level on " + ticker + " " + side + " at $"
                                + FixedPoint.format(priceMicros, 4) + ": "
                                + FixedPoint.format(size, 2)
                                + ". Zero levels must be removed, not stored.");
            }
            if (priceMicros < 0 || priceMicros > FixedPoint.ONE) {
                throw new IllegalStateException(
                        "level price out of [0, $1.00] on " + ticker + " " + side + ": $"
                                + FixedPoint.format(priceMicros, 4));
            }
        }
    }

    /** Thrown when the two best bids sum above $1.00. */
    public static final class CrossedBookException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final transient MarketTicker ticker;

        CrossedBookException(MarketTicker ticker, Price yesBid, Price noBid) {
            super("crossed book on " + ticker + ": best YES bid " + yesBid
                    + " + best NO bid " + noBid + " = $"
                    + FixedPoint.format(yesBid.micros() + noBid.micros(), 4)
                    + " > $1.00. The matching engine would already have matched these, so this "
                    + "is a delta-application or gap-recovery bug, not an opportunity.");
            this.ticker = ticker;
        }

        public MarketTicker ticker() {
            return ticker;
        }
    }
}
