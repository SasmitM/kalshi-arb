package com.kalshiarb.kalshi.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.kalshiarb.kalshi.rest.dto.EventSummary;
import com.kalshiarb.kalshi.rest.dto.ExchangeStatus;
import com.kalshiarb.kalshi.rest.dto.MarketSummary;
import com.kalshiarb.kalshi.rest.dto.Orderbook;
import com.kalshiarb.kalshi.rest.dto.OrderbookResponse;
import com.kalshiarb.kalshi.rest.dto.Pages;
import com.kalshiarb.kalshi.rest.dto.SeriesDetail;
import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.Price;
import com.kalshiarb.model.Side;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Parses the pinned live captures. No network access. */
class DtoParsingTest {

    @Nested
    @DisplayName("GET /exchange/status")
    class ExchangeStatusTest {

        @Test
        @DisplayName("parses all four exchange shards")
        void parses() {
            ExchangeStatus status = Samples.parse("exchange_status.json", ExchangeStatus.class);

            assertThat(status.isUp()).isTrue();
            assertThat(status.isTradingActive()).isTrue();
            assertThat(status.exchangeIndexStatuses()).hasSize(4);
            assertThat(status.exchangeIndexStatuses())
                    .extracting(ExchangeStatus.IndexStatus::exchangeIndex)
                    .containsExactly(0, 1, 2, 3);
            assertThat(status.exchangeIndexStatuses())
                    .extracting(ExchangeStatus.IndexStatus::description)
                    .containsExactly("Default", "Combos", "Crypto", "Tennis & Baseball");
        }
    }

    @Nested
    @DisplayName("GET /series/{ticker}")
    class SeriesTest {

        @Test
        @DisplayName("exposes the per-series fee type and multiplier")
        void parsesFeeFields() {
            SeriesDetail series =
                    Samples.parse("series_KXHIGHNY.json", Pages.SeriesResponse.class).series();

            assertThat(series.ticker()).isEqualTo("KXHIGHNY");
            assertThat(series.title()).isEqualTo("Highest temperature in NYC");
            assertThat(series.category()).isEqualTo("Climate and Weather");
            assertThat(series.frequency()).isEqualTo("daily");
            // The fee model reads these instead of hardcoding a constant.
            assertThat(series.feeType()).isEqualTo("quadratic");
            assertThat(series.feeMultiplier()).isEqualTo("1");
            assertThat(series.exchangeIndex()).isZero();
        }
    }

    @Nested
    @DisplayName("GET /events")
    class EventsTest {

        @Test
        @DisplayName("parses a page and ignores the unmodelled milestones field")
        void parsesPage() {
            Pages.EventsPage page = Samples.parse("events_KXHIGHNY.json", Pages.EventsPage.class);

            assertThat(page.events()).isNotEmpty();
            assertThat(page.hasMore()).isTrue();
            assertThat(page.cursor()).isNotBlank();

            EventSummary first = page.events().get(0);
            assertThat(first.eventTicker()).isEqualTo("KXHIGHNY-26SEP01");
            assertThat(first.seriesTicker()).isEqualTo("KXHIGHNY");
            assertThat(first.event().seriesTicker()).isEqualTo(first.series());
        }

        @Test
        @DisplayName("mutually_exclusive is true — this is what licenses the bracket-sum rule")
        void mutuallyExclusiveIsSet() {
            EventSummary event = Samples.parse(
                    "event_KXHIGHNY-26SEP01_nested.json", Pages.EventResponse.class).event();

            assertThat(event.isMutuallyExclusive()).isTrue();
            assertThat(event.subTitle()).isEqualTo("On Sep 1, 2026");
            assertThat(event.collateralReturnType()).isEqualTo("MECNET");
        }

        @Test
        @DisplayName("an absent mutually_exclusive flag reads as false, never assumed true")
        void absentFlagIsFalse() {
            EventSummary bare = new EventSummary(
                    "X-1", "X", null, null, null, null, null, null, null, null);
            assertThat(bare.isMutuallyExclusive()).isFalse();
        }
    }

    @Nested
    @DisplayName("GET /markets")
    class MarketsTest {

        private Pages.MarketsPage page() {
            return Samples.parse("markets_KXHIGHNY-26SEP01.json", Pages.MarketsPage.class);
        }

        @Test
        @DisplayName("parses all six brackets of the event")
        void parsesSixBrackets() {
            Pages.MarketsPage page = page();

            assertThat(page.markets()).hasSize(6);
            assertThat(page.markets())
                    .extracting(MarketSummary::ticker)
                    .containsExactlyInAnyOrder(
                            "KXHIGHNY-26SEP01-B87.5", "KXHIGHNY-26SEP01-B85.5",
                            "KXHIGHNY-26SEP01-T83", "KXHIGHNY-26SEP01-T90",
                            "KXHIGHNY-26SEP01-B83.5", "KXHIGHNY-26SEP01-B89.5");
            assertThat(page.markets()).allSatisfy(m -> {
                assertThat(m.isActive()).isTrue();
                assertThat(m.event().value()).isEqualTo("KXHIGHNY-26SEP01");
                assertThat(m.exchangeIndex()).isZero();
                assertThat(m.marketType()).isEqualTo("binary");
            });
        }

        @Test
        @DisplayName("the ragged _dollars fields all convert to exact micro-dollars")
        void parsesPrices() {
            MarketSummary b87 = page().markets().stream()
                    .filter(m -> m.ticker().equals("KXHIGHNY-26SEP01-B87.5"))
                    .findFirst().orElseThrow();

            assertThat(b87.yesBid()).contains(Price.ofCents(5));
            assertThat(b87.yesAsk()).contains(Price.ofCents(6));
            assertThat(b87.noBid()).contains(Price.ofCents(94));
            assertThat(b87.noAsk()).contains(Price.ofCents(95));
            assertThat(b87.lastPrice()).contains(Price.ofCents(5));

            // yes_bid + no_bid <= $1.00 and yes_ask == complement(no_bid): the implied-ask
            // identity holds on the quote fields, not just in the book.
            assertThat(b87.yesAsk().orElseThrow())
                    .isEqualTo(b87.noBid().orElseThrow().complement());
        }

        @Test
        @DisplayName("volume_24h_fp parses — the digit-run naming trap")
        void parsesVolume24h() {
            // A snake_case strategy alone maps volume24hFp to "volume24h_fp" and this silently
            // reads null. The explicit @JsonProperty is what makes this pass.
            assertThat(page().markets())
                    .allSatisfy(m -> assertThat(m.volume24hFp()).isNotNull());

            MarketSummary b87 = page().markets().stream()
                    .filter(m -> m.ticker().equals("KXHIGHNY-26SEP01-B87.5"))
                    .findFirst().orElseThrow();
            assertThat(b87.volume24hFp()).isEqualTo("15.00");
            assertThat(b87.volumeFp()).isEqualTo("15.00");
            assertThat(b87.openInterest().orElseThrow().toContractString()).isEqualTo("14.00");
        }

        @Test
        @DisplayName("strike fields keep exact wire text, and are off by one from the sub-title")
        void strikeFieldsAreRawText() {
            List<MarketSummary> markets = page().markets();

            MarketSummary t83 = markets.stream()
                    .filter(m -> m.ticker().equals("KXHIGHNY-26SEP01-T83"))
                    .findFirst().orElseThrow();

            // The trap: cap_strike is 83 but the market means "82 or below".
            assertThat(t83.strikeType()).isEqualTo("less");
            assertThat(t83.capStrike()).isEqualTo("83");
            assertThat(t83.floorStrike()).isNull();
            assertThat(t83.yesSubTitle()).isEqualTo("82° or below");

            MarketSummary b87 = markets.stream()
                    .filter(m -> m.ticker().equals("KXHIGHNY-26SEP01-B87.5"))
                    .findFirst().orElseThrow();
            assertThat(b87.strikeType()).isEqualTo("between");
            assertThat(b87.floorStrike()).isEqualTo("87");
            assertThat(b87.capStrike()).isEqualTo("88");
            assertThat(b87.yesSubTitle()).isEqualTo("87° to 88°");
        }

        @Test
        @DisplayName("the six brackets tile the range with no gap or overlap")
        void bracketsAreExhaustive() {
            // ≤82, 83-84, 85-86, 87-88, 89-90, ≥91
            assertThat(page().markets())
                    .extracting(MarketSummary::yesSubTitle)
                    .containsExactlyInAnyOrder(
                            "82° or below", "83° to 84°", "85° to 86°",
                            "87° to 88°", "89° to 90°", "91° or above");
        }
    }

    @Nested
    @DisplayName("GET /markets/{ticker}/orderbook")
    class OrderbookTest {

        private Orderbook t83() {
            return Samples.parse("orderbook_KXHIGHNY-26SEP01-T83.json", OrderbookResponse.class)
                    .toOrderbook(MarketTicker.of("KXHIGHNY-26SEP01-T83"));
        }

        @Test
        @DisplayName("decodes both bid sides, including fractional counts")
        void decodesLevels() {
            Orderbook book = t83();

            assertThat(book.yesBids()).hasSize(34);
            assertThat(book.noBids()).hasSize(32);
            assertThat(book.isTwoSided()).isTrue();

            // ["0.0600", "102.12"] on the NO side.
            assertThat(book.noBids())
                    .anySatisfy(l -> {
                        assertThat(l.price()).isEqualTo(Price.ofCents(6));
                        assertThat(l.quantity().toContractString()).isEqualTo("102.12");
                    });
        }

        @Test
        @DisplayName("best bid is computed, not taken from wire order")
        void derivesBestBid() {
            Orderbook book = t83();

            assertThat(book.bestBid(Side.YES).orElseThrow().price()).isEqualTo(Price.ofCents(50));
            assertThat(book.bestBid(Side.NO).orElseThrow().price()).isEqualTo(Price.ofCents(48));
        }

        @Test
        @DisplayName("asks are implied by complementing the opposite side's best bid")
        void derivesImpliedAsk() {
            Orderbook book = t83();

            // Best NO bid $0.48 implies a YES ask at $0.52.
            assertThat(book.bestAsk(Side.YES)).contains(Price.ofCents(52));
            // Best YES bid $0.50 implies a NO ask at $0.50.
            assertThat(book.bestAsk(Side.NO)).contains(Price.ofCents(50));
        }

        @Test
        @DisplayName("the spread is identical on both sides, as the algebra requires")
        void spreadIsSideIndependent() {
            Orderbook book = t83();

            // $0.52 ask - $0.50 bid = $0.02
            assertThat(book.spread(Side.YES)).contains(Price.ofCents(2));
            assertThat(book.spread(Side.NO)).contains(book.spread(Side.YES).orElseThrow());
        }

        @Test
        @DisplayName("the crossed-book invariant holds on both real captures")
        void invariantHolds() {
            t83().checkNotCrossed();

            Samples.parse("orderbook_KXHIGHNY-26SEP01-B85.5.json", OrderbookResponse.class)
                    .toOrderbook(MarketTicker.of("KXHIGHNY-26SEP01-B85.5"))
                    .checkNotCrossed();
        }

        @Test
        @DisplayName("depth sums every level on a side")
        void sumsDepth() {
            Orderbook book = t83();

            assertThat(book.depth(Side.YES).microContracts()).isPositive();
            long manual = book.yesBids().stream()
                    .mapToLong(l -> l.quantity().microContracts()).sum();
            assertThat(book.depth(Side.YES).microContracts()).isEqualTo(manual);
        }

        @Test
        @DisplayName("an empty or absent side yields no bid, no ask, and no spread")
        void handlesEmptySides() {
            Orderbook empty = new OrderbookResponse(null)
                    .toOrderbook(MarketTicker.of("KXHIGHNY-26SEP01-T83"));

            assertThat(empty.bestBid(Side.YES)).isEmpty();
            assertThat(empty.bestAsk(Side.YES)).isEmpty();
            assertThat(empty.spread(Side.YES)).isEmpty();
            assertThat(empty.isTwoSided()).isFalse();
            // Vacuously true rather than throwing.
            empty.checkNotCrossed();
        }
    }
}
