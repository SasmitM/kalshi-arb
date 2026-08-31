package com.kalshiarb.kalshi.rest.dto;

import com.kalshiarb.model.FixedPoint;
import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.Price;
import com.kalshiarb.model.PriceLevel;
import com.kalshiarb.model.Quantity;
import com.kalshiarb.model.Side;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A point-in-time order book for one market, decoded from
 * {@code GET /markets/{ticker}/orderbook}.
 *
 * <p>Kalshi publishes <b>bids only, on both sides</b>. There is no ask book. An ask is derived
 * by complementing the opposite side's best bid, because a NO bid at $0.45 is by definition a
 * YES ask at $0.55 — the two contracts are minted as a pair for $1.00.
 *
 * <p>Levels are held in wire order but every accessor computes its own extremum rather than
 * trusting that order. Real payloads happen to arrive ascending by price, but relying on that
 * would be an undocumented assumption that fails silently.
 *
 * @param ticker  the market these levels belong to
 * @param yesBids resting bids on the YES side
 * @param noBids  resting bids on the NO side
 */
public record Orderbook(MarketTicker ticker, List<PriceLevel> yesBids, List<PriceLevel> noBids) {

    public Orderbook {
        yesBids = List.copyOf(yesBids);
        noBids = List.copyOf(noBids);
    }

    public List<PriceLevel> bids(Side side) {
        return side == Side.YES ? yesBids : noBids;
    }

    /** Highest resting bid on {@code side}, or empty if that side of the book is empty. */
    public Optional<PriceLevel> bestBid(Side side) {
        return bids(side).stream().max(Comparator.comparing(PriceLevel::price));
    }

    /**
     * Best ask for {@code side}, implied from the opposite side's best bid.
     *
     * <p>Empty when the opposite side has no bids: with nothing to complement there is no
     * offer, which is different from an offer at $1.00.
     */
    public Optional<Price> bestAsk(Side side) {
        return bestBid(side.opposite()).map(level -> level.price().complement());
    }

    /**
     * Ask minus bid on {@code side}, or empty if either half is missing.
     *
     * <p>Identical for both sides — the YES and NO spreads are the same number, since both are
     * $1.00 minus the sum of the two best bids.
     */
    public Optional<Price> spread(Side side) {
        Optional<PriceLevel> bid = bestBid(side);
        Optional<Price> ask = bestAsk(side);
        if (bid.isEmpty() || ask.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ask.get().minus(bid.get().price()));
    }

    /** Total size resting on {@code side}, summed across levels. */
    public Quantity depth(Side side) {
        long total = 0;
        for (PriceLevel level : bids(side)) {
            total += level.quantity().microContracts();
        }
        return new Quantity(total);
    }

    /**
     * Asserts the fundamental Kalshi invariant: {@code best_yes_bid + best_no_bid <= $1.00}.
     *
     * <p>A sum above $1.00 is a crossed book, which cannot exist on a live feed: the matching
     * engine would already have matched those two orders and minted a contract pair. So a
     * violation is never an arbitrage opportunity — it is a decode or delta-application bug,
     * and treating it as a signal would mean trading on our own broken state.
     *
     * <p>This is also the structural reason the project targets cross-market constraints:
     * intra-market YES/NO arbitrage is impossible by construction.
     *
     * @throws CrossedBookException if the two best bids sum above $1.00
     */
    public void checkNotCrossed() {
        Optional<PriceLevel> yes = bestBid(Side.YES);
        Optional<PriceLevel> no = bestBid(Side.NO);
        if (yes.isEmpty() || no.isEmpty()) {
            return;
        }
        // Deliberately summed in raw micros rather than via Price.plus: Price is bounded to
        // $1.00, so plus() would throw a generic range error and lose the diagnostic.
        long sum = yes.get().price().micros() + no.get().price().micros();
        if (sum > FixedPoint.ONE) {
            throw new CrossedBookException(ticker, yes.get().price(), no.get().price());
        }
    }

    /** True when both sides have at least one resting bid. */
    public boolean isTwoSided() {
        return !yesBids.isEmpty() && !noBids.isEmpty();
    }

    /** Thrown when the crossed-book invariant fails, i.e. when our own state is wrong. */
    public static final class CrossedBookException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        CrossedBookException(MarketTicker ticker, Price yesBid, Price noBid) {
            super("crossed book on " + ticker + ": best YES bid " + yesBid + " + best NO bid "
                    + noBid + " = " + FixedPoint.format(yesBid.micros() + noBid.micros(), 4)
                    + " > $1.00. This is a decode/delta bug, not an opportunity.");
        }
    }
}
