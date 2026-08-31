package com.kalshiarb.kalshi.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kalshiarb.kalshi.rest.dto.Orderbook;
import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.Price;
import com.kalshiarb.model.PriceLevel;
import com.kalshiarb.model.Quantity;
import com.kalshiarb.model.Side;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Synthetic books, for the boundary cases the live captures cannot exhibit. */
class OrderbookInvariantTest {

    private static final MarketTicker TICKER = MarketTicker.of("KXHIGHNY-26SEP01-T83");

    private static Orderbook book(long yesBidCents, long noBidCents) {
        return new Orderbook(TICKER,
                List.of(new PriceLevel(Price.ofCents(yesBidCents), Quantity.ofContracts(10))),
                List.of(new PriceLevel(Price.ofCents(noBidCents), Quantity.ofContracts(10))));
    }

    @Test
    @DisplayName("bids summing to exactly $1.00 are legal — a zero spread, not a crossed book")
    void exactlyOneDollarIsLegal() {
        Orderbook touching = book(60, 40);

        touching.checkNotCrossed();
        assertThat(touching.spread(Side.YES)).contains(Price.ZERO);
        assertThat(touching.bestAsk(Side.YES)).contains(Price.ofCents(60));
    }

    @Test
    @DisplayName("bids summing above $1.00 throw, and the message says it is a bug")
    void crossedBookThrows() {
        assertThatThrownBy(() -> book(60, 41).checkNotCrossed())
                .isInstanceOf(Orderbook.CrossedBookException.class)
                .hasMessageContaining("crossed book on KXHIGHNY-26SEP01-T83")
                .hasMessageContaining("$0.6000")
                .hasMessageContaining("$0.4100")
                .hasMessageContaining("1.0100")
                .hasMessageContaining("not an opportunity");
    }

    @Test
    @DisplayName("a one-cent crossing is caught, not absorbed by rounding")
    void detectsSmallestCrossing() {
        // 999_999 micros is under $1.00 and legal; 1_000_001 is not.
        Orderbook justUnder = new Orderbook(TICKER,
                List.of(new PriceLevel(new Price(500_000), Quantity.ofContracts(1))),
                List.of(new PriceLevel(new Price(499_999), Quantity.ofContracts(1))));
        justUnder.checkNotCrossed();

        Orderbook justOver = new Orderbook(TICKER,
                List.of(new PriceLevel(new Price(500_001), Quantity.ofContracts(1))),
                List.of(new PriceLevel(new Price(500_000), Quantity.ofContracts(1))));
        assertThatThrownBy(justOver::checkNotCrossed)
                .isInstanceOf(Orderbook.CrossedBookException.class);
    }

    @Test
    @DisplayName("a one-sided book has a bid but no ask, and passes the invariant vacuously")
    void oneSidedBook() {
        Orderbook yesOnly = new Orderbook(TICKER,
                List.of(new PriceLevel(Price.ofCents(30), Quantity.ofContracts(5))),
                List.of());

        yesOnly.checkNotCrossed();
        assertThat(yesOnly.bestBid(Side.YES)).isPresent();
        assertThat(yesOnly.bestAsk(Side.YES)).isEmpty();
        assertThat(yesOnly.bestBid(Side.NO)).isEmpty();
        // A NO ask is implied by the YES bid even with no NO bids resting.
        assertThat(yesOnly.bestAsk(Side.NO)).contains(Price.ofCents(70));
        assertThat(yesOnly.isTwoSided()).isFalse();
    }

    @Test
    @DisplayName("best bid ignores wire order")
    void bestBidIgnoresWireOrder() {
        // Descending on purpose; real payloads are ascending.
        Orderbook descending = new Orderbook(TICKER,
                List.of(
                        new PriceLevel(Price.ofCents(40), Quantity.ofContracts(1)),
                        new PriceLevel(Price.ofCents(10), Quantity.ofContracts(1)),
                        new PriceLevel(Price.ofCents(25), Quantity.ofContracts(1))),
                List.of());

        assertThat(descending.bestBid(Side.YES).orElseThrow().price())
                .isEqualTo(Price.ofCents(40));
    }

    @Test
    @DisplayName("the level lists are defensively copied")
    void levelsAreImmutable() {
        Orderbook b = book(30, 30);
        assertThatThrownBy(() -> b.yesBids().add(
                new PriceLevel(Price.ofCents(99), Quantity.ofContracts(1))))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
