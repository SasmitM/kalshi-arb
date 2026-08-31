package com.kalshiarb.book;

import static org.assertj.core.api.Assertions.assertThat;

import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.Money;
import com.kalshiarb.model.Price;
import com.kalshiarb.model.PriceLevel;
import com.kalshiarb.model.Quantity;
import com.kalshiarb.model.Side;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DepthWalkerTest {

    private static final MarketTicker TICKER = MarketTicker.of("KXHIGHNY-26SEP01-T83");

    private static PriceLevel level(long cents, long contracts) {
        return new PriceLevel(Price.ofCents(cents), Quantity.ofContracts(contracts));
    }

    /**
     * NO bids at 45/44/43 imply YES asks at 55/56/57 with sizes 10/20/30.
     * YES bids at 30/29 give sell-side liquidity of 5/15.
     */
    private static Book ladder() {
        Book b = new Book(TICKER);
        b.applySnapshot(
                List.of(level(29, 15), level(30, 5)),
                List.of(level(43, 30), level(44, 20), level(45, 10)));
        return b;
    }

    @Test
    @DisplayName("buying walks the OPPOSITE ladder, cheapest ask first")
    void buyWalksOppositeLadderAscendingInAsk() {
        // 25 YES: 10 @ $0.55 + 15 @ $0.56 = 5.50 + 8.40 = $13.90
        DepthWalker.Fill fill =
                DepthWalker.costToBuy(ladder(), Side.YES, Quantity.ofContracts(25));

        assertThat(fill.isComplete()).isTrue();
        assertThat(fill.filled()).isEqualTo(Quantity.ofContracts(25));
        assertThat(fill.cash()).isEqualTo(Money.fromFixedPointString("13.9000"));
        // Worst price is the highest ask touched -- cost must get worse with size, never better.
        assertThat(fill.worstPrice()).isEqualTo(Price.ofCents(56));
        assertThat(fill.levelsUsed()).isEqualTo(2);
    }

    @Test
    @DisplayName("buying only the top level costs exactly the touch")
    void buyWithinTopLevel() {
        DepthWalker.Fill fill =
                DepthWalker.costToBuy(ladder(), Side.YES, Quantity.ofContracts(10));

        assertThat(fill.cash()).isEqualTo(Money.fromFixedPointString("5.5000"));
        assertThat(fill.worstPrice()).isEqualTo(Price.ofCents(55));
        assertThat(fill.levelsUsed()).isEqualTo(1);
        assertThat(fill.averagePrice()).contains(Price.ofCents(55));
    }

    @Test
    @DisplayName("the average price is worse than the touch once several levels are consumed")
    void averagePriceDegradesWithSize() {
        Book b = ladder();
        Price touch = ImpliedAsk.bestAsk(b, Side.YES).orElseThrow();

        DepthWalker.Fill fill = DepthWalker.costToBuy(b, Side.YES, Quantity.ofContracts(60));

        assertThat(fill.isComplete()).isTrue();
        // 10@0.55 + 20@0.56 + 30@0.57 = 5.50 + 11.20 + 17.10 = $33.80 over 60 -> $0.5633..
        assertThat(fill.cash()).isEqualTo(Money.fromFixedPointString("33.8000"));
        Price avg = fill.averagePrice().orElseThrow();
        assertThat(avg.micros()).isEqualTo(563_333L);
        assertThat(avg.isGreaterThan(touch))
                .as("this gap is exactly what makes a top-of-book edge evaporate with size")
                .isTrue();
    }

    @Test
    @DisplayName("an oversized request fills partially and reports incomplete")
    void oversizedRequestIsIncomplete() {
        // Only 60 contracts of YES are buyable in total.
        DepthWalker.Fill fill =
                DepthWalker.costToBuy(ladder(), Side.YES, Quantity.ofContracts(100));

        assertThat(fill.isComplete()).isFalse();
        assertThat(fill.filled()).isEqualTo(Quantity.ofContracts(60));
        assertThat(fill.cash()).isEqualTo(Money.fromFixedPointString("33.8000"));
        assertThat(fill.levelsUsed()).isEqualTo(3);
    }

    @Test
    @DisplayName("an empty opposite ladder means nothing is buyable at any price")
    void nothingBuyableWithoutOppositeBids() {
        Book b = new Book(TICKER);
        b.applySnapshot(List.of(level(30, 5)), List.of());

        DepthWalker.Fill fill = DepthWalker.costToBuy(b, Side.YES, Quantity.ofContracts(10));

        assertThat(fill.isEmpty()).isTrue();
        assertThat(fill.isComplete()).isFalse();
        assertThat(fill.cash()).isEqualTo(Money.ZERO);
        assertThat(fill.worstPrice()).isNull();
        assertThat(fill.averagePrice()).isEmpty();
        assertThat(DepthWalker.buyableSize(b, Side.YES)).isEqualTo(Quantity.ZERO);
    }

    @Test
    @DisplayName("selling walks its own ladder, best bid first")
    void sellWalksOwnLadder() {
        // 12 YES sold: 5 @ $0.30 + 7 @ $0.29 = 1.50 + 2.03 = $3.53
        DepthWalker.Fill fill =
                DepthWalker.proceedsFromSell(ladder(), Side.YES, Quantity.ofContracts(12));

        assertThat(fill.isComplete()).isTrue();
        assertThat(fill.cash()).isEqualTo(Money.fromFixedPointString("3.5300"));
        assertThat(fill.worstPrice()).isEqualTo(Price.ofCents(29));
        assertThat(fill.levelsUsed()).isEqualTo(2);
    }

    @Test
    @DisplayName("buyable size is the whole opposite ladder")
    void buyableSizeIsOppositeDepth() {
        Book b = ladder();

        assertThat(DepthWalker.buyableSize(b, Side.YES)).isEqualTo(Quantity.ofContracts(60));
        assertThat(DepthWalker.buyableSize(b, Side.NO)).isEqualTo(Quantity.ofContracts(20));
    }

    @Test
    @DisplayName("fractional sizes are exact, since real levels hold 0.33 contracts")
    void fractionalSizesAreExact() {
        Book b = new Book(TICKER);
        b.applySnapshot(
                List.of(),
                List.of(new PriceLevel(Price.ofCents(60), new Quantity(330_000L))));

        // 0.33 contracts of YES at $0.40 = $0.132
        DepthWalker.Fill fill = DepthWalker.costToBuy(b, Side.YES, new Quantity(330_000L));

        assertThat(fill.isComplete()).isTrue();
        assertThat(fill.cash().micros()).isEqualTo(132_000L);
        assertThat(fill.averagePrice()).contains(Price.ofCents(40));
    }

    @Test
    @DisplayName("a one-contract fill at a cheap level is exact, the size that fees punish")
    void singleContractFill() {
        Book b = new Book(TICKER);
        b.applySnapshot(List.of(), List.of(level(90, 1)));

        // The live shape: a 3c gross edge whose binding size was 1.00 contract.
        DepthWalker.Fill fill = DepthWalker.costToBuy(b, Side.YES, Quantity.ofContracts(1));

        assertThat(fill.isComplete()).isTrue();
        assertThat(fill.cash()).isEqualTo(Money.ofCents(10));
        assertThat(fill.averagePrice()).contains(Price.ofCents(10));
    }

    @Test
    @DisplayName("requesting zero fills nothing and costs nothing")
    void zeroRequest() {
        DepthWalker.Fill fill = DepthWalker.costToBuy(ladder(), Side.YES, Quantity.ZERO);

        assertThat(fill.isEmpty()).isTrue();
        assertThat(fill.isComplete()).isTrue();
        assertThat(fill.cash()).isEqualTo(Money.ZERO);
        assertThat(fill.levelsUsed()).isZero();
    }
}
