package com.kalshiarb.detector;

import com.kalshiarb.book.Book;
import com.kalshiarb.book.DepthWalker;
import com.kalshiarb.model.EventTicker;
import com.kalshiarb.model.FixedPoint;
import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.Money;
import com.kalshiarb.model.Price;
import com.kalshiarb.model.Quantity;
import com.kalshiarb.model.Side;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The exhaustive-bracket constraint: a day's high temperature falls in exactly one bracket, so
 * the brackets' prices must sum to $1.00.
 *
 * <p>No probability is ever estimated. The check is purely that two prices cannot both be
 * correct, which is why it needs no view on the weather.
 *
 * <h2>The two directions are not symmetric</h2>
 * <ul>
 *   <li><b>Sell side</b> — short a subset for {@code sum(bid)}. Exactly one bracket wins, so the
 *       liability is $1.00 if the winner is in the subset and $0 otherwise. Dropping a leg only
 *       lowers the payout, never raises it, so {@code sum(bid) > $1.00} is a real violation over
 *       <b>any subset</b>. An illiquid bracket with no bid does not invalidate it.
 *   <li><b>Buy side</b> — long the set for {@code sum(ask)}. The $1.00 only arrives if the winner
 *       is in the set, so the guarantee needs <b>every</b> bracket. One unquoted leg and there is
 *       no constraint to test.
 * </ul>
 * This matters in practice: live {@code KXHIGHNY-26SEP01-T90} had zero YES bids, so it was
 * unsellable while remaining buyable.
 *
 * <h2>Prices come from a depth walk, not the touch</h2>
 * A top-of-book edge is only executable for the size resting at the top, which on these markets
 * is routinely one or two contracts. Every price here is the average achieved for the whole size,
 * so the edge shrinks as size grows — which is the honest cost curve and usually what kills the
 * trade.
 *
 * <p><b>Pure.</b> No I/O, no clock, no network — a function of book state alone. That is what
 * lets a replay drive byte-identical detector code against archived data.
 */
public final class ExhaustiveSumConstraint {

    private final FeeModel fees;

    public ExhaustiveSumConstraint(FeeModel fees) {
        this.fees = fees;
    }

    /**
     * Evaluates both directions for one event's brackets at a given size.
     *
     * @param event    the event family; its markets must be mutually exclusive and exhaustive,
     *                 which is asserted by the caller from the event's {@code mutually_exclusive}
     *                 flag rather than inferred from ticker shape
     * @param brackets every bracket in the set, in stable order
     * @param size     contracts per leg to price
     */
    public List<Opportunity> evaluate(EventTicker event, List<Book> brackets, Quantity size) {
        List<Opportunity> found = new ArrayList<>(2);
        sellSide(event, brackets, size).ifPresent(found::add);
        buySide(event, brackets, size).ifPresent(found::add);
        return found;
    }

    /**
     * {@code sum(bid) > $1.00} over the brackets that can actually be sold.
     *
     * <p>Legs with no bid are skipped rather than disqualifying the check, because omitting a
     * bracket only reduces the $1.00 liability.
     */
    public Optional<Opportunity> sellSide(
            EventTicker event, List<Book> brackets, Quantity size) {

        List<Opportunity.Leg> legs = new ArrayList<>();
        long proceeds = 0;
        long feeTotal = 0;
        long minAvailable = Long.MAX_VALUE;
        MarketTicker binding = null;

        for (Book book : brackets) {
            DepthWalker.Fill fill = DepthWalker.proceedsFromSell(book, Side.YES, size);
            if (fill.isEmpty()) {
                continue; // unsellable leg; legitimately skipped on this side
            }
            Price avg = fill.averagePrice().orElseThrow();
            Money fee = fees.orderFee(fill.filled(), avg);

            proceeds += fill.cash().micros();
            feeTotal += fee.micros();
            legs.add(new Opportunity.Leg(book.ticker(), avg, fill.filled(), fee));

            if (fill.filled().microContracts() < minAvailable) {
                minAvailable = fill.filled().microContracts();
                binding = book.ticker();
            }
        }
        if (legs.isEmpty()) {
            return Optional.empty();
        }

        // Liability is $1.00 per contract regardless of how many legs were sold.
        long liability = scale(FixedPoint.ONE, minAvailable);
        long grossAtBindingSize = rescale(proceeds, size.microContracts(), minAvailable)
                - liability;
        if (grossAtBindingSize <= 0) {
            return Optional.empty();
        }
        return Optional.of(new Opportunity(
                event, Opportunity.Direction.SELL_BRACKETS, legs, new Quantity(minAvailable),
                new Money(grossAtBindingSize), new Money(feeTotal),
                new Money(grossAtBindingSize - feeTotal), binding));
    }

    /**
     * {@code sum(ask) < $1.00} over <b>every</b> bracket.
     *
     * <p>Returns empty if any bracket is unbuyable: an unquoted bracket can still win, so the
     * $1.00 payout would not be guaranteed and there is no constraint to test.
     */
    public Optional<Opportunity> buySide(
            EventTicker event, List<Book> brackets, Quantity size) {

        List<Opportunity.Leg> legs = new ArrayList<>();
        long cost = 0;
        long feeTotal = 0;
        long minAvailable = Long.MAX_VALUE;
        MarketTicker binding = null;

        for (Book book : brackets) {
            DepthWalker.Fill fill = DepthWalker.costToBuy(book, Side.YES, size);
            if (fill.isEmpty()) {
                // Coverage is mandatory on this side.
                return Optional.empty();
            }
            Price avg = fill.averagePrice().orElseThrow();
            Money fee = fees.orderFee(fill.filled(), avg);

            cost += fill.cash().micros();
            feeTotal += fee.micros();
            legs.add(new Opportunity.Leg(book.ticker(), avg, fill.filled(), fee));

            if (fill.filled().microContracts() < minAvailable) {
                minAvailable = fill.filled().microContracts();
                binding = book.ticker();
            }
        }
        if (legs.isEmpty()) {
            return Optional.empty();
        }

        long payout = scale(FixedPoint.ONE, minAvailable);
        long grossAtBindingSize = payout
                - rescale(cost, size.microContracts(), minAvailable);
        if (grossAtBindingSize <= 0) {
            return Optional.empty();
        }
        return Optional.of(new Opportunity(
                event, Opportunity.Direction.BUY_BRACKETS, legs, new Quantity(minAvailable),
                new Money(grossAtBindingSize), new Money(feeTotal),
                new Money(grossAtBindingSize - feeTotal), binding));
    }

    /**
     * The smallest size at which an opportunity clears fees, or empty if it never does.
     *
     * <p>Exists because the per-order cent ceiling punishes small orders out of all proportion —
     * one contract at $0.10 owes 0.63c and pays a full cent — so an opportunity can be
     * unprofitable at one contract and profitable at fifty with identical prices. Searching
     * upward from one contract is the honest way to answer "is this tradable at all", and the
     * answer is frequently no, because the size that would clear fees exceeds the depth
     * available.
     *
     * @param maxSize the depth ceiling; the search never proposes more than the book can absorb
     */
    public Optional<Quantity> minViableSize(
            EventTicker event, List<Book> brackets, Quantity maxSize,
            Opportunity.Direction direction) {

        long cap = maxSize.microContracts();
        if (cap <= 0) {
            return Optional.empty();
        }
        // Whole contracts only: fractional orders are not something to reason about here, and
        // the ceiling makes the function non-monotonic below one contract anyway.
        for (long contracts = 1; contracts * FixedPoint.ONE <= cap; contracts++) {
            Quantity size = Quantity.ofContracts(contracts);
            Optional<Opportunity> opportunity = direction == Opportunity.Direction.SELL_BRACKETS
                    ? sellSide(event, brackets, size)
                    : buySide(event, brackets, size);
            if (opportunity.isPresent() && opportunity.get().isProfitable()) {
                return Optional.of(size);
            }
        }
        return Optional.empty();
    }

    /** price * size / ONE, in micro-dollars. */
    private static long scale(long priceMicros, long microContracts) {
        return Math.multiplyExact(priceMicros, microContracts) / FixedPoint.ONE;
    }

    /**
     * Rescales cash computed for {@code requested} down to {@code actual} size.
     *
     * <p>A depth walk may fill less than asked on some legs; the trade is only executable at the
     * thinnest leg's size, so every leg's cash has to be restated at that size before the edge
     * means anything.
     */
    private static long rescale(long cash, long requested, long actual) {
        if (requested == actual || requested == 0) {
            return cash;
        }
        return Math.multiplyExact(cash, actual) / requested;
    }
}
