package com.kalshiarb.detector;

import static org.assertj.core.api.Assertions.assertThat;

import com.kalshiarb.book.Book;
import com.kalshiarb.model.EventTicker;
import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.Money;
import com.kalshiarb.model.Price;
import com.kalshiarb.model.PriceLevel;
import com.kalshiarb.model.Quantity;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MonotoneThresholdConstraintTest {

    private static final EventTicker EVENT = EventTicker.of("KXBTCD-26SEP0117");

    private final MonotoneThresholdConstraint constraint =
            new MonotoneThresholdConstraint(FeeModel.weather());

    /**
     * A threshold market quoted {@code bid}/{@code ask} in cents for {@code size} contracts.
     *
     * <p>An ask of {@code a} is a NO bid at {@code 1 - a}, since Kalshi publishes bids only.
     */
    private static Book quoted(String ticker, long bidCents, long askCents, long size) {
        Book b = new Book(MarketTicker.of(ticker));
        b.applySnapshot(
                List.of(new PriceLevel(Price.ofCents(bidCents), Quantity.ofContracts(size))),
                List.of(new PriceLevel(Price.ofCents(100 - askCents),
                        Quantity.ofContracts(size))));
        return b;
    }

    @Test
    @DisplayName("a healthy monotone chain yields nothing")
    void healthyChainIsClean() {
        // Real KXBTCD shape: price falls as the threshold rises, spreads normal.
        List<Book> chain = List.of(
                quoted("KXBTCD-26SEP0117-T75499.99", 96, 98, 100),
                quoted("KXBTCD-26SEP0117-T75999.99", 96, 97, 100),
                quoted("KXBTCD-26SEP0117-T76499.99", 93, 94, 100),
                quoted("KXBTCD-26SEP0117-T76999.99", 91, 92, 100));

        assertThat(constraint.evaluate(EVENT, chain, Quantity.ofContracts(100))).isEmpty();
    }

    @Test
    @DisplayName("a quote inversion that a spread explains is NOT an opportunity")
    void inversionWithinSpreadIsNotTradeable() {
        // The live pair observed on KXBTCD: bid rises with the threshold (0.96 -> 0.97), which
        // is anomalous, but ask(lower)=0.98 is still above bid(higher)=0.97 so there is no
        // riskless trade. Reporting this would be a false positive.
        List<Book> chain = List.of(
                quoted("KXBTCD-26SEP0117-T75499.99", 96, 98, 100),
                quoted("KXBTCD-26SEP0117-T75749.99", 97, 98, 100));

        assertThat(constraint.evaluate(EVENT, chain, Quantity.ofContracts(100))).isEmpty();
    }

    @Test
    @DisplayName("ask(lower) < bid(higher) IS a riskless two-leg arbitrage")
    void trueViolationIsDetected() {
        // Buy >=75,500 at 0.90 and sell >=75,999 at 0.95: paid 5c to hold a payoff that is
        // never negative.
        List<Book> chain = List.of(
                quoted("KXBTCD-26SEP0117-T75499.99", 88, 90, 100),
                quoted("KXBTCD-26SEP0117-T75999.99", 95, 97, 100));

        List<Opportunity> found =
                constraint.evaluate(EVENT, chain, Quantity.ofContracts(100));

        assertThat(found).hasSize(1);
        Opportunity opp = found.get(0);
        assertThat(opp.direction()).isEqualTo(Opportunity.Direction.MONOTONE_PAIR);
        assertThat(opp.legs()).hasSize(2);
        // 0.95 - 0.90 = 5c per contract, $5.00 over 100.
        assertThat(opp.grossEdge()).isEqualTo(Money.fromFixedPointString("5.0000"));
    }

    @Test
    @DisplayName("TWO legs beats five: this survives fees where a bracket sum would not")
    void twoLegsSurviveFees() {
        List<Book> chain = List.of(
                quoted("KXBTCD-26SEP0117-T75499.99", 88, 90, 100),
                quoted("KXBTCD-26SEP0117-T75999.99", 95, 97, 100));

        Opportunity opp = constraint
                .evaluate(EVENT, chain, Quantity.ofContracts(100)).get(0);

        // Fees: 0.07*100*0.90*0.10 = 0.63 and 0.07*100*0.95*0.05 = 0.3325 -> 0.34. Total 0.97.
        assertThat(opp.totalFees()).isEqualTo(Money.fromFixedPointString("0.9700"));
        assertThat(opp.netEdge()).isEqualTo(Money.fromFixedPointString("4.0300"));
        assertThat(opp.isProfitable())
                .as("a 5c two-leg edge clears ~1c of fees, unlike a 4c five-leg edge")
                .isTrue();
        assertThat(opp.feeBurdenPercent()).isLessThan(25.0);
    }

    @Test
    @DisplayName("fees near $0.50 are far heavier than in the tails, for the same gross edge")
    void feeBurdenIsPriceDependent() {
        // Same 5c gross edge, once in the tail and once at the parabola's peak.
        List<Book> tail = List.of(
                quoted("KXBTCD-26SEP0117-T1", 3, 5, 100),
                quoted("KXBTCD-26SEP0117-T2", 10, 12, 100));
        List<Book> middle = List.of(
                quoted("KXBTCD-26SEP0117-T3", 45, 47, 100),
                quoted("KXBTCD-26SEP0117-T4", 52, 54, 100));

        Opportunity tailOpp = constraint.evaluate(EVENT, tail, Quantity.ofContracts(100)).get(0);
        Opportunity midOpp = constraint.evaluate(EVENT, middle, Quantity.ofContracts(100)).get(0);

        assertThat(tailOpp.grossEdge()).isEqualTo(midOpp.grossEdge());
        // This is precisely why a constant cent threshold is always wrong.
        assertThat(midOpp.totalFees().micros())
                .isGreaterThan(tailOpp.totalFees().micros() * 3);
    }

    @Test
    @DisplayName("non-adjacent pairs are checked, not just neighbours")
    void checksNonAdjacentPairs() {
        // Every adjacent pair is fine; only the (first, last) pair violates.
        List<Book> chain = List.of(
                quoted("KXBTCD-26SEP0117-T1", 50, 52, 100),
                quoted("KXBTCD-26SEP0117-T2", 48, 60, 100),
                quoted("KXBTCD-26SEP0117-T3", 55, 57, 100));

        List<Opportunity> found = constraint.evaluate(EVENT, chain, Quantity.ofContracts(100));

        assertThat(found).isNotEmpty();
        assertThat(found.get(0).legs())
                .extracting(l -> l.market().value())
                .containsExactly("KXBTCD-26SEP0117-T1", "KXBTCD-26SEP0117-T3");
    }

    @Test
    @DisplayName("size is capped by the thinner leg")
    void sizeCappedByThinnerLeg() {
        List<Book> chain = List.of(
                quoted("KXBTCD-26SEP0117-T1", 88, 90, 100),
                quoted("KXBTCD-26SEP0117-T2", 95, 97, 4));

        Opportunity opp = constraint.evaluate(EVENT, chain, Quantity.ofContracts(100)).get(0);

        assertThat(opp.size()).isEqualTo(Quantity.ofContracts(4));
        assertThat(opp.bindingLeg()).isEqualTo(MarketTicker.of("KXBTCD-26SEP0117-T2"));
    }

    @Test
    @DisplayName("a missing leg yields nothing — there is no subset argument here")
    void missingLegYieldsNothing() {
        // Unlike the bracket sum, the hedge requires BOTH legs to trade.
        List<Book> chain = List.of(
                new Book(MarketTicker.of("KXBTCD-26SEP0117-T1")),
                quoted("KXBTCD-26SEP0117-T2", 95, 97, 100));

        assertThat(constraint.evaluate(EVENT, chain, Quantity.ofContracts(10))).isEmpty();
    }

    @Test
    @DisplayName("results are ordered best net edge first")
    void resultsOrderedByNetEdge() {
        List<Book> chain = List.of(
                quoted("KXBTCD-26SEP0117-T1", 40, 42, 100),
                quoted("KXBTCD-26SEP0117-T2", 50, 52, 100),
                quoted("KXBTCD-26SEP0117-T3", 60, 62, 100));

        List<Opportunity> found = constraint.evaluate(EVENT, chain, Quantity.ofContracts(100));

        assertThat(found).hasSizeGreaterThan(1);
        for (int i = 1; i < found.size(); i++) {
            assertThat(found.get(i - 1).netEdge().micros())
                    .isGreaterThanOrEqualTo(found.get(i).netEdge().micros());
        }
    }

    @Test
    @DisplayName("a reversed ordering would invert the test, so the contract is caller-enforced")
    void reversedOrderingReportsSpuriousViolations() {
        // Documents the hazard: a healthy ascending-strike chain, listed descending, looks
        // violating. The ordering contract is stated in the javadoc for exactly this reason.
        List<Book> healthy = List.of(
                quoted("KXBTCD-26SEP0117-T1", 90, 92, 100),
                quoted("KXBTCD-26SEP0117-T2", 40, 42, 100));
        assertThat(constraint.evaluate(EVENT, healthy, Quantity.ofContracts(100))).isEmpty();

        List<Book> reversed = List.of(healthy.get(1), healthy.get(0));
        assertThat(constraint.evaluate(EVENT, reversed, Quantity.ofContracts(100)))
                .as("reversed input inverts the relation; ordering is the caller's job")
                .isNotEmpty();
    }
}
