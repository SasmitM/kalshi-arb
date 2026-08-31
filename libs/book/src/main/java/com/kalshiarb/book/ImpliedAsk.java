package com.kalshiarb.book;

import com.kalshiarb.model.Price;
import com.kalshiarb.model.Quantity;
import com.kalshiarb.model.Side;
import java.util.Optional;

/**
 * Derives asks from the opposite side's bids.
 *
 * <p>Kalshi mints YES and NO as a pair for $1.00, so buying NO at $0.45 is economically identical
 * to selling YES at $0.55. A resting NO bid is therefore <em>already</em> a YES offer, and there
 * is no separate ask book to publish. Every ask in this system is
 * {@code $1.00 - oppositeBestBid}.
 *
 * <p>Two consequences worth keeping in mind:
 *
 * <ul>
 *   <li><b>An ask's size is the opposite bid's size.</b> The contracts you would buy come from
 *       whoever is bidding on the other side.
 *   <li><b>No opposite bid means no ask at all</b> — which is different from an ask at $1.00.
 *       {@link Optional#empty()} models "you cannot buy this at any price", and a live
 *       {@code KXHIGHNY-26SEP01-T90} really was in that state on the YES side.
 * </ul>
 *
 * <p>Pure functions over a {@link Book}; no state, no clock, no I/O.
 */
public final class ImpliedAsk {

    private ImpliedAsk() {
    }

    /** Best (lowest) ask for {@code side}, implied from the opposite side's best bid. */
    public static Optional<Price> bestAsk(Book book, Side side) {
        return book.bestBid(side.opposite()).map(Price::complement);
    }

    /** Size available at the best ask for {@code side}, i.e. the opposite best bid's size. */
    public static Optional<Quantity> bestAskSize(Book book, Side side) {
        return book.bestBidSize(side.opposite());
    }

    /**
     * Ask minus bid on {@code side}, or empty if either half is missing.
     *
     * <p>Identical for both sides: both reduce to {@code $1.00 - (yesBid + noBid)}. A test pins
     * that, because a spread that differed by side would mean the complement rule had been
     * applied inconsistently somewhere.
     */
    public static Optional<Price> spread(Book book, Side side) {
        Optional<Price> bid = book.bestBid(side);
        Optional<Price> ask = bestAsk(book, side);
        if (bid.isEmpty() || ask.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ask.get().minus(bid.get()));
    }

    /**
     * Midpoint of the bid and implied ask on {@code side}.
     *
     * <p>Rounds down on an odd micro total. This is a display and diagnostic figure only —
     * the project never estimates a true probability, so a mid is never an input to a decision.
     */
    public static Optional<Price> mid(Book book, Side side) {
        Optional<Price> bid = book.bestBid(side);
        Optional<Price> ask = bestAsk(book, side);
        if (bid.isEmpty() || ask.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Price((bid.get().micros() + ask.get().micros()) / 2));
    }
}
