package com.kalshiarb.detector;

import static org.assertj.core.api.Assertions.assertThat;

import com.kalshiarb.book.Book;
import com.kalshiarb.model.EventTicker;
import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.Money;
import com.kalshiarb.model.Price;
import com.kalshiarb.model.PriceLevel;
import com.kalshiarb.model.Quantity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExhaustiveSumConstraintTest {

    private static final EventTicker EVENT = EventTicker.of("KXHIGHNY-26SEP01");

    private final ExhaustiveSumConstraint constraint =
            new ExhaustiveSumConstraint(FeeModel.weather());

    /**
     * A bracket whose YES is buyable at {@code askCents} for {@code size} contracts.
     *
     * <p>Buying YES consumes NO bids, so an ask of {@code a} means a NO bid at {@code 1 - a}.
     */
    private static Book buyableAt(String ticker, long askCents, long size) {
        Book b = new Book(MarketTicker.of(ticker));
        b.applySnapshot(List.of(), List.of(new PriceLevel(
                Price.ofCents(100 - askCents), Quantity.ofContracts(size))));
        return b;
    }

    /** A bracket whose YES is sellable at {@code bidCents} for {@code size} contracts. */
    private static Book sellableAt(String ticker, long bidCents, long size) {
        Book b = new Book(MarketTicker.of(ticker));
        b.applySnapshot(
                List.of(new PriceLevel(Price.ofCents(bidCents), Quantity.ofContracts(size))),
                List.of());
        return b;
    }

    @Test
    @DisplayName("CLAUDE.md's $0.96 five-bracket example MUST come out negative")
    void theMandatedNegativeExample() {
        // Five brackets whose asks sum to $0.96: a 4c gross edge that looks risk-free.
        // Fees at these prices total ~5.2c per contract, so the trade LOSES about 1.2c.
        List<Book> brackets = List.of(
                buyableAt("KXHIGHNY-26SEP01-T83", 30, 100),
                buyableAt("KXHIGHNY-26SEP01-B83.5", 25, 100),
                buyableAt("KXHIGHNY-26SEP01-B85.5", 20, 100),
                buyableAt("KXHIGHNY-26SEP01-B87.5", 13, 100),
                buyableAt("KXHIGHNY-26SEP01-B89.5", 8, 100));

        Opportunity opp = constraint
                .buySide(EVENT, brackets, Quantity.ofContracts(100))
                .orElseThrow(() -> new AssertionError("gross violation should be detected"));

        // The gross edge is real: $1.00 - $0.96 = 4c per contract, $4.00 over 100.
        assertThat(opp.grossEdge()).isEqualTo(Money.fromFixedPointString("4.0000"));

        // Fees, each ceiled to a cent at 100 contracts:
        //   0.30 -> 1.47   0.25 -> 1.3125->1.32   0.20 -> 1.12
        //   0.13 -> 0.7917->0.80   0.08 -> 0.5152->0.52     total $5.23
        assertThat(opp.totalFees()).isEqualTo(Money.fromFixedPointString("5.2300"));

        // THE POINT: net is negative.
        assertThat(opp.netEdge().micros()).isEqualTo(-1_230_000L);
        assertThat(opp.netEdge().isNegative()).isTrue();
        assertThat(opp.isProfitable()).isFalse();
        assertThat(opp.feeBurdenPercent()).isGreaterThan(100.0);
        assertThat(opp.legs()).hasSize(5);
    }

    @Test
    @DisplayName("no size clears fees on the $0.96 example, at any depth")
    void mandatedExampleHasNoViableSize() {
        List<Book> brackets = List.of(
                buyableAt("KXHIGHNY-26SEP01-T83", 30, 100),
                buyableAt("KXHIGHNY-26SEP01-B83.5", 25, 100),
                buyableAt("KXHIGHNY-26SEP01-B85.5", 20, 100),
                buyableAt("KXHIGHNY-26SEP01-B87.5", 13, 100),
                buyableAt("KXHIGHNY-26SEP01-B89.5", 8, 100));

        // The 4c edge is below the ~5.2c unrounded fee, so scale cannot rescue it. Growing the
        // order only makes the loss bigger.
        assertThat(constraint.minViableSize(EVENT, brackets, Quantity.ofContracts(100),
                Opportunity.Direction.BUY_BRACKETS)).isEmpty();
    }

    @Test
    @DisplayName("a wide enough edge does clear fees, so the model is not just always negative")
    void aRealEdgeSurvives() {
        // Asks summing to $0.80: a 20c gross edge, far above the ~5c fee load.
        List<Book> brackets = List.of(
                buyableAt("KXHIGHNY-26SEP01-T83", 25, 50),
                buyableAt("KXHIGHNY-26SEP01-B83.5", 20, 50),
                buyableAt("KXHIGHNY-26SEP01-B85.5", 15, 50),
                buyableAt("KXHIGHNY-26SEP01-B87.5", 12, 50),
                buyableAt("KXHIGHNY-26SEP01-B89.5", 8, 50));

        Opportunity opp = constraint
                .buySide(EVENT, brackets, Quantity.ofContracts(50)).orElseThrow();

        assertThat(opp.grossEdge()).isEqualTo(Money.fromFixedPointString("10.0000"));
        assertThat(opp.isProfitable()).isTrue();
        assertThat(opp.netEdge().micros()).isPositive();
    }

    @Test
    @DisplayName("BUY side requires EVERY bracket quoted — one unbuyable leg means no constraint")
    void buySideNeedsFullCoverage() {
        List<Book> brackets = new ArrayList<>(List.of(
                buyableAt("KXHIGHNY-26SEP01-T83", 25, 50),
                buyableAt("KXHIGHNY-26SEP01-B83.5", 20, 50),
                buyableAt("KXHIGHNY-26SEP01-B85.5", 15, 50)));
        // The live T90 shape: no NO bids at all, so YES is unbuyable.
        brackets.add(new Book(MarketTicker.of("KXHIGHNY-26SEP01-T90")));

        // An unquoted bracket can still win, so the $1.00 payout is not guaranteed.
        assertThat(constraint.buySide(EVENT, brackets, Quantity.ofContracts(10))).isEmpty();
    }

    @Test
    @DisplayName("SELL side is valid over a SUBSET — a missing leg only lowers the liability")
    void sellSideWorksOnSubsets() {
        // Five sellable brackets bidding 0.30/0.25/0.22/0.20/0.15 = $1.12, plus one unsellable.
        List<Book> brackets = new ArrayList<>(List.of(
                sellableAt("KXHIGHNY-26SEP01-T83", 30, 40),
                sellableAt("KXHIGHNY-26SEP01-B83.5", 25, 40),
                sellableAt("KXHIGHNY-26SEP01-B85.5", 22, 40),
                sellableAt("KXHIGHNY-26SEP01-B87.5", 20, 40),
                sellableAt("KXHIGHNY-26SEP01-B89.5", 15, 40)));
        brackets.add(new Book(MarketTicker.of("KXHIGHNY-26SEP01-T90")));

        Opportunity opp = constraint
                .sellSide(EVENT, brackets, Quantity.ofContracts(40))
                .orElseThrow(() -> new AssertionError(
                        "sum(bid) > $1.00 is a violation even with an unquoted leg"));

        // Only the five sellable legs are traded; the sixth is skipped, not disqualifying.
        assertThat(opp.legs()).hasSize(5);
        // $1.12 collected, $1.00 liability -> 12c gross per contract, $4.80 over 40.
        assertThat(opp.grossEdge()).isEqualTo(Money.fromFixedPointString("4.8000"));
        assertThat(opp.direction()).isEqualTo(Opportunity.Direction.SELL_BRACKETS);
    }

    @Test
    @DisplayName("bracket sums at or under $1.00 on the buy side are not opportunities")
    void noEdgeWhenSumIsFair() {
        List<Book> exactlyOne = List.of(
                buyableAt("KXHIGHNY-26SEP01-T83", 50, 10),
                buyableAt("KXHIGHNY-26SEP01-B83.5", 50, 10));

        assertThat(constraint.buySide(EVENT, exactlyOne, Quantity.ofContracts(10))).isEmpty();

        List<Book> overOne = List.of(
                buyableAt("KXHIGHNY-26SEP01-T83", 60, 10),
                buyableAt("KXHIGHNY-26SEP01-B83.5", 50, 10));
        assertThat(constraint.buySide(EVENT, overOne, Quantity.ofContracts(10))).isEmpty();
    }

    @Test
    @DisplayName("size is capped by the THINNEST leg, and that leg is reported")
    void sizeIsCappedByThinnestLeg() {
        List<Book> brackets = List.of(
                buyableAt("KXHIGHNY-26SEP01-T83", 25, 50),
                buyableAt("KXHIGHNY-26SEP01-B83.5", 20, 3),   // the binding leg
                buyableAt("KXHIGHNY-26SEP01-B85.5", 15, 50),
                buyableAt("KXHIGHNY-26SEP01-B87.5", 12, 50),
                buyableAt("KXHIGHNY-26SEP01-B89.5", 8, 50));

        Opportunity opp = constraint
                .buySide(EVENT, brackets, Quantity.ofContracts(50)).orElseThrow();

        assertThat(opp.size()).isEqualTo(Quantity.ofContracts(3));
        assertThat(opp.bindingLeg())
                .isEqualTo(MarketTicker.of("KXHIGHNY-26SEP01-B83.5"));
    }

    @Test
    @DisplayName("a 3c edge on ONE contract loses to fees — the live shape observed today")
    void threeCentEdgeOnOneContractLoses() {
        // The real KXHIGHNY sell-side sum was $1.03 over 5 legs with 1.00 contract of depth.
        List<Book> brackets = List.of(
                sellableAt("KXHIGHNY-26SEP01-T83", 35, 1),
                sellableAt("KXHIGHNY-26SEP01-B83.5", 29, 1),
                sellableAt("KXHIGHNY-26SEP01-B85.5", 20, 1),
                sellableAt("KXHIGHNY-26SEP01-B87.5", 12, 1),
                sellableAt("KXHIGHNY-26SEP01-B89.5", 7, 1));

        Opportunity opp = constraint
                .sellSide(EVENT, brackets, Quantity.ofContracts(1)).orElseThrow();

        // $1.03 collected, $1.00 liability -> 3c gross.
        assertThat(opp.grossEdge()).isEqualTo(Money.ofCents(3));
        // Five legs, each rounded up to at least a cent -> at least 5c of fees.
        assertThat(opp.totalFees().micros()).isGreaterThanOrEqualTo(50_000L);
        assertThat(opp.isProfitable()).isFalse();
    }

    @Test
    @DisplayName("minViableSize finds the threshold where the cent ceiling stops dominating")
    void minViableSizeFindsThreshold() {
        // A 6c gross edge against ~2.4c of unrounded fees: unprofitable at one contract because
        // five legs each round up to a cent, profitable once size dilutes the rounding.
        List<Book> brackets = List.of(
                sellableAt("KXHIGHNY-26SEP01-T83", 40, 200),
                sellableAt("KXHIGHNY-26SEP01-B83.5", 30, 200),
                sellableAt("KXHIGHNY-26SEP01-B85.5", 20, 200),
                sellableAt("KXHIGHNY-26SEP01-B87.5", 10, 200),
                sellableAt("KXHIGHNY-26SEP01-B89.5", 6, 200));

        Optional<Quantity> min = constraint.minViableSize(
                EVENT, brackets, Quantity.ofContracts(200),
                Opportunity.Direction.SELL_BRACKETS);

        assertThat(min).isPresent();
        // Profitable at the threshold, and it is a real threshold rather than 1 contract.
        Quantity size = min.orElseThrow();
        assertThat(constraint.sellSide(EVENT, brackets, size).orElseThrow().isProfitable())
                .isTrue();
    }

    @Test
    @DisplayName("evaluate returns nothing when there is no violation in either direction")
    void evaluateQuietMarket() {
        List<Book> brackets = List.of(
                buyableAt("KXHIGHNY-26SEP01-T83", 60, 10),
                buyableAt("KXHIGHNY-26SEP01-B83.5", 45, 10));

        assertThat(constraint.evaluate(EVENT, brackets, Quantity.ofContracts(10))).isEmpty();
    }

    @Test
    @DisplayName("an empty bracket set yields nothing rather than dividing by zero")
    void emptyBracketSet() {
        assertThat(constraint.sellSide(EVENT, List.of(), Quantity.ofContracts(1))).isEmpty();
        assertThat(constraint.buySide(EVENT, List.of(), Quantity.ofContracts(1))).isEmpty();
        assertThat(constraint.minViableSize(EVENT, List.of(), Quantity.ZERO,
                Opportunity.Direction.BUY_BRACKETS)).isEmpty();
    }
}
