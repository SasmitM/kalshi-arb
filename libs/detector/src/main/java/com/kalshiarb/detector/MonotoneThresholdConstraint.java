package com.kalshiarb.detector;

import com.kalshiarb.book.Book;
import com.kalshiarb.book.DepthWalker;
import com.kalshiarb.model.EventTicker;
import com.kalshiarb.model.FixedPoint;
import com.kalshiarb.model.Money;
import com.kalshiarb.model.Price;
import com.kalshiarb.model.Quantity;
import com.kalshiarb.model.Side;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Nested-threshold monotonicity: {@code price(X >= 60) >= price(X >= 65)}, always.
 *
 * <p>Every world in which the value clears 65 is also a world in which it clears 60, so the lower
 * threshold's contract pays out in a strict superset of cases. No probability is estimated; the
 * relation is pure logic about which events contain which.
 *
 * <h2>The arbitrage, and why it is riskless</h2>
 * Suppose {@code ask(>=60) < bid(>=65)} — the contract that dominates is cheaper than the one it
 * dominates. Buy YES at 60 and sell YES at 65:
 *
 * <ul>
 *   <li>{@code X >= 65}: the long pays $1, the short owes $1 — net $0.
 *   <li>{@code 60 <= X < 65}: the long pays $1, the short owes nothing — net +$1.
 *   <li>{@code X < 60}: neither pays — net $0.
 * </ul>
 *
 * The payoff is never negative, and the position is entered for {@code ask(>=60) - bid(>=65)},
 * which is <b>negative</b> — you are paid to hold a non-negative payoff. That is the violation
 * condition: {@code ask(lower) < bid(higher)}.
 *
 * <h2>Why this constraint matters more than the bracket sum</h2>
 * It is a <b>two-leg</b> trade. Fees are charged per leg, so a two-leg opportunity pays two fees
 * where an exhaustive bracket set pays five or six. On the live data, five-leg bracket violations
 * lost to fees by a factor of 2.5; a two-leg structure starts with far less to overcome.
 *
 * <h2>Ordering contract</h2>
 * Callers pass books ordered so that price <b>must be non-increasing</b> along the list:
 *
 * <ul>
 *   <li>{@code greater} thresholds: ascending strike ({@code >=60}, {@code >=65}, {@code >=70}).
 *   <li>{@code less} thresholds: descending cap ({@code <=70}, {@code <=65}, {@code <=60}).
 * </ul>
 *
 * Getting the order backwards inverts the test and reports every healthy market as violating, so
 * the ordering is the caller's responsibility and is stated here rather than guessed from
 * tickers — strike fields are off by one from the sub-title, so ticker text cannot be trusted.
 *
 * <p>All pairs are checked, not just adjacent ones: adjacent asks being well-behaved does not
 * imply a distant pair is, and the quadratic cost is irrelevant here — CLAUDE.md is explicit that
 * the detector is not where the latency budget goes.
 *
 * <p><b>Pure.</b> No I/O, no clock, no network.
 */
public final class MonotoneThresholdConstraint {

    private final FeeModel fees;

    public MonotoneThresholdConstraint(FeeModel fees) {
        this.fees = fees;
    }

    /**
     * Finds every violating pair, priced at {@code size} with a depth walk and fees.
     *
     * @param event    the event these thresholds belong to
     * @param ordered  books ordered so price must be non-increasing; see the ordering contract
     * @param size     contracts per leg to price
     * @return violations, best net edge first
     */
    public List<Opportunity> evaluate(EventTicker event, List<Book> ordered, Quantity size) {
        List<Opportunity> found = new ArrayList<>();
        for (int lower = 0; lower < ordered.size(); lower++) {
            for (int higher = lower + 1; higher < ordered.size(); higher++) {
                pair(event, ordered.get(lower), ordered.get(higher), size).ifPresent(found::add);
            }
        }
        found.sort((a, b) -> Long.compare(b.netEdge().micros(), a.netEdge().micros()));
        return found;
    }

    /**
     * Prices one pair: buy {@code dominating} (the lower threshold), sell {@code dominated}.
     *
     * <p>Empty unless {@code ask(dominating) < bid(dominated)}, which is the only configuration
     * that cannot be explained by an ordinary spread.
     */
    public Optional<Opportunity> pair(
            EventTicker event, Book dominating, Book dominated, Quantity size) {

        DepthWalker.Fill buy = DepthWalker.costToBuy(dominating, Side.YES, size);
        DepthWalker.Fill sell = DepthWalker.proceedsFromSell(dominated, Side.YES, size);
        if (buy.isEmpty() || sell.isEmpty()) {
            // No ask on one side or no bid on the other: nothing to compare, and unlike the
            // bracket sum there is no subset argument that rescues a missing leg here — the
            // hedge only works if both legs trade.
            return Optional.empty();
        }

        // The trade is only executable at the thinner leg's size.
        long executable = Math.min(buy.filled().microContracts(), sell.filled().microContracts());
        if (executable <= 0) {
            return Optional.empty();
        }

        Price buyPrice = buy.averagePrice().orElseThrow();
        Price sellPrice = sell.averagePrice().orElseThrow();

        // Violation condition. Using depth-walked averages, not the touch, so a one-contract
        // inversion at the top of book does not masquerade as a scalable opportunity.
        if (buyPrice.micros() >= sellPrice.micros()) {
            return Optional.empty();
        }

        long grossPerContract = sellPrice.micros() - buyPrice.micros();
        long gross = Math.multiplyExact(grossPerContract, executable) / FixedPoint.ONE;

        Quantity executableQty = new Quantity(executable);
        Money buyFee = fees.orderFee(executableQty, buyPrice);
        Money sellFee = fees.orderFee(executableQty, sellPrice);
        Money totalFees = buyFee.plus(sellFee);

        List<Opportunity.Leg> legs = List.of(
                new Opportunity.Leg(dominating.ticker(), buyPrice, buy.filled(), buyFee),
                new Opportunity.Leg(dominated.ticker(), sellPrice, sell.filled(), sellFee));

        return Optional.of(new Opportunity(
                event, Opportunity.Direction.MONOTONE_PAIR, legs, executableQty,
                new Money(gross), totalFees, new Money(gross - totalFees.micros()),
                buy.filled().microContracts() <= sell.filled().microContracts()
                        ? dominating.ticker() : dominated.ticker()));
    }

    /**
     * The smallest whole-contract size at which the best pair clears fees, or empty if none does.
     *
     * <p>Unlike the bracket sum this can genuinely flip with size, because two legs means only
     * two cent-ceilings to amortise.
     */
    public Optional<Quantity> minViableSize(
            EventTicker event, List<Book> ordered, Quantity maxSize) {
        long cap = maxSize.microContracts();
        for (long contracts = 1; contracts * FixedPoint.ONE <= cap; contracts++) {
            Quantity size = Quantity.ofContracts(contracts);
            for (Opportunity opp : evaluate(event, ordered, size)) {
                if (opp.isProfitable()) {
                    return Optional.of(size);
                }
            }
        }
        return Optional.empty();
    }
}
