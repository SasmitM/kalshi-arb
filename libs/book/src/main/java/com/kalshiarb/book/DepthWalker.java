package com.kalshiarb.book;

import com.kalshiarb.model.FixedPoint;
import com.kalshiarb.model.Money;
import com.kalshiarb.model.Price;
import com.kalshiarb.model.Quantity;
import com.kalshiarb.model.Side;
import java.util.Map;

/**
 * Walks the ladder to answer "what would N contracts actually cost?".
 *
 * <p>The reason this matters more than the top of book: a bracket-sum violation computed from best
 * prices is only executable for the size resting at those prices, and on Kalshi weather markets
 * that is routinely one or two contracts. A live run showed a 3c gross edge whose binding size was
 * <b>1.00 contract</b>. Task 7's minimum-viable-size calculation has to know the real cost curve,
 * not the touch.
 *
 * <h2>Buying walks the OPPOSITE ladder</h2>
 * There is no ask book. To buy YES you consume NO bids: the cheapest YES ask is
 * {@code $1.00 - highestNoBid}, so walking NO bids from the highest price downward walks YES asks
 * from the lowest price upward. Getting this backwards produces a cost curve that improves as you
 * buy more, which is the sort of wrong that looks like free money.
 *
 * <p>Selling walks its own ladder: to sell YES you hit YES bids, highest first.
 *
 * <p>Pure functions; no state, no clock, no I/O.
 */
public final class DepthWalker {

    private DepthWalker() {
    }

    /**
     * Result of walking the ladder for a target size.
     *
     * @param requested  size asked for
     * @param filled     size actually available, less than requested if the ladder ran out
     * @param cash       total cost to buy, or total proceeds from selling
     * @param worstPrice the least favourable price touched, absent if nothing filled
     * @param levelsUsed how many price levels were consumed
     */
    public record Fill(
            Quantity requested,
            Quantity filled,
            Money cash,
            Price worstPrice,
            int levelsUsed) {

        /** True when the whole requested size was available. */
        public boolean isComplete() {
            return filled.microContracts() >= requested.microContracts();
        }

        public boolean isEmpty() {
            return filled.microContracts() == 0;
        }

        /**
         * Cash per contract actually filled, or empty if nothing filled.
         *
         * <p>This, not the touch price, is the number a fee model and an edge calculation should
         * use once size is involved.
         */
        public java.util.Optional<Price> averagePrice() {
            if (isEmpty()) {
                return java.util.Optional.empty();
            }
            // cash is micro-dollars and filled is micro-contracts, so the naive ratio comes out
            // in dollars-per-contract with the scale divided away. Multiply by ONE first to land
            // back in micro-dollars, and do it with multiplyExact so an overflow is loud rather
            // than a plausible-looking wrong price.
            long avg = Math.multiplyExact(cash.micros(), FixedPoint.ONE)
                    / filled.microContracts();
            return java.util.Optional.of(new Price(avg));
        }

        @Override
        public String toString() {
            return "Fill[requested=" + requested.toContractString()
                    + " filled=" + filled.toContractString()
                    + " cash=" + cash
                    + " worst=" + (worstPrice == null ? "-" : worstPrice.toString())
                    + " levels=" + levelsUsed
                    + (isComplete() ? "" : " INCOMPLETE") + "]";
        }
    }

    /**
     * Cost to buy {@code target} contracts of {@code side}, walking implied asks.
     *
     * <p>Walks the opposite ladder from the highest bid downward, because
     * {@code ask = $1.00 - oppositeBid} means the highest opposite bid is the cheapest ask.
     */
    public static Fill costToBuy(Book book, Side side, Quantity target) {
        long remaining = target.microContracts();
        long cash = 0;
        long worst = -1;
        int levels = 0;

        // descendingMap on the opposite ladder = ascending in ask price.
        for (Map.Entry<Long, Long> entry : book.ladder(side.opposite()).descendingMap()
                .entrySet()) {
            if (remaining <= 0) {
                break;
            }
            long askMicros = FixedPoint.ONE - entry.getKey();
            long available = entry.getValue();
            long take = Math.min(remaining, available);

            cash += scale(askMicros, take);
            remaining -= take;
            worst = askMicros;
            levels++;
        }

        long filled = target.microContracts() - remaining;
        return new Fill(target, new Quantity(filled), new Money(cash),
                worst < 0 ? null : new Price(worst), levels);
    }

    /**
     * Proceeds from selling {@code target} contracts of {@code side}, walking that side's bids
     * from the highest downward.
     */
    public static Fill proceedsFromSell(Book book, Side side, Quantity target) {
        long remaining = target.microContracts();
        long cash = 0;
        long worst = -1;
        int levels = 0;

        for (Map.Entry<Long, Long> entry : book.ladder(side).descendingMap().entrySet()) {
            if (remaining <= 0) {
                break;
            }
            long bidMicros = entry.getKey();
            long take = Math.min(remaining, entry.getValue());

            cash += scale(bidMicros, take);
            remaining -= take;
            worst = bidMicros;
            levels++;
        }

        long filled = target.microContracts() - remaining;
        return new Fill(target, new Quantity(filled), new Money(cash),
                worst < 0 ? null : new Price(worst), levels);
    }

    /**
     * Total size buyable on {@code side}, i.e. everything resting on the opposite ladder.
     *
     * <p>The hard ceiling on any opportunity in this market.
     */
    public static Quantity buyableSize(Book book, Side side) {
        return book.totalDepth(side.opposite());
    }

    /** price(micro-dollars) * size(micro-contracts) / ONE, exactly, in micro-dollars. */
    private static long scale(long priceMicros, long microContracts) {
        return Math.multiplyExact(priceMicros, microContracts) / FixedPoint.ONE;
    }
}
