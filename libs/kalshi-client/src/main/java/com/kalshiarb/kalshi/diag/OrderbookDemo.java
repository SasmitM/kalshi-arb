package com.kalshiarb.kalshi.diag;

import com.kalshiarb.common.Clock;
import com.kalshiarb.kalshi.rest.KalshiRestClient;
import com.kalshiarb.kalshi.rest.dto.EventSummary;
import com.kalshiarb.kalshi.rest.dto.ExchangeStatus;
import com.kalshiarb.kalshi.rest.dto.MarketSummary;
import com.kalshiarb.kalshi.rest.dto.Orderbook;
import com.kalshiarb.kalshi.rest.dto.SeriesDetail;
import com.kalshiarb.model.EventTicker;
import com.kalshiarb.model.FixedPoint;
import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.Price;
import com.kalshiarb.model.PriceLevel;
import com.kalshiarb.model.Side;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Prints live order books for one event's brackets, with derived best bid, implied best ask and
 * spread, and asserts the crossed-book invariant on every one.
 *
 * <p>Also sums the bracket asks and bids across the event. That sum is the raw input to the
 * exhaustive-bracket constraint, shown here <em>before</em> any fee adjustment — which is the
 * honest way to look at it, because the whole question of the project is what survives fees and
 * latency. A sum below $1.00 on the ask side looks like free money and almost never is.
 *
 * <p>Entirely unauthenticated: every endpoint touched here is public.
 *
 * <p>Usage: {@code OrderbookDemo [EVENT_TICKER]}, default {@code KXHIGHNY-26SEP01}.
 */
public final class OrderbookDemo {

    private static final String DEFAULT_EVENT = "KXHIGHNY-26SEP01";
    private static final int DEPTH_ROWS = 5;

    private OrderbookDemo() {
    }

    public static void main(String[] args) {
        EventTicker event = EventTicker.of(args.length > 0 ? args[0] : DEFAULT_EVENT);
        Clock clock = Clock.system();

        try (KalshiRestClient client = KalshiRestClient.publicOnly(clock)) {
            ExchangeStatus status = client.exchangeStatus();
            System.out.printf("exchange: active=%s trading=%s%n",
                    status.isUp(), status.isTradingActive());
            if (!status.isUp()) {
                System.out.println("exchange is down; books below may be stale");
            }

            SeriesDetail series = client.series(event.seriesTicker());
            System.out.printf("series  : %s  \"%s\"  fee=%s x%s%n",
                    series.ticker(), series.title(), series.feeType(), series.feeMultiplier());

            List<MarketSummary> markets = new ArrayList<>(client.marketsForEvent(event));
            if (markets.isEmpty()) {
                System.err.println("no markets under " + event
                        + " -- it may have settled. Pass an open event ticker as argv[1].");
                System.exit(1);
            }
            markets.sort(Comparator.comparing(MarketSummary::ticker));

            Optional<EventSummary> meta = client.events(event.seriesTicker(), 200, null).events()
                    .stream()
                    .filter(e -> e.eventTicker().equals(event.value()))
                    .findFirst();
            System.out.printf("event   : %s  markets=%d  mutually_exclusive=%s%n%n",
                    event, markets.size(),
                    meta.map(EventSummary::isMutuallyExclusive)
                            .map(String::valueOf).orElse("unknown"));

            long askSumMicros = 0;
            long bidSumMicros = 0;
            int quotedBoth = 0;
            int sellableLegs = 0;
            int buyableLegs = 0;
            long minSellSize = Long.MAX_VALUE;
            long totalFetchMicros = 0;

            for (MarketSummary market : markets) {
                MarketTicker ticker = market.marketTicker();

                long t0 = clock.nanoTime();
                Orderbook book = client.orderbook(ticker);
                long fetchMicros = (clock.nanoTime() - t0) / 1_000L;
                totalFetchMicros += fetchMicros;

                // The invariant. A violation is a decode bug, so fail loudly rather than print.
                book.checkNotCrossed();

                Optional<PriceLevel> yesBid = book.bestBid(Side.YES);
                Optional<Price> yesAsk = book.bestAsk(Side.YES);
                Optional<Price> spread = book.spread(Side.YES);

                System.out.printf("%-26s %-14s  levels %2d/%-2d  %5.1f ms%n",
                        ticker.value(),
                        market.yesSubTitle() == null ? "" : market.yesSubTitle(),
                        book.yesBids().size(), book.noBids().size(),
                        fetchMicros / 1000.0);
                System.out.printf("    YES  bid %-9s ask %-9s spread %-9s%n",
                        yesBid.map(l -> l.price().toString()).orElse("-"),
                        yesAsk.map(Price::toString).orElse("-"),
                        spread.map(Price::toString).orElse("-"));
                System.out.printf("    depth YES %s   NO %s%n",
                        book.depth(Side.YES).toContractString(),
                        book.depth(Side.NO).toContractString());
                printTopLevels(book);

                if (yesBid.isPresent() && yesAsk.isPresent()) {
                    quotedBoth++;
                }
                // Sellable: there is a YES bid to hit. Size is bounded by that level.
                if (yesBid.isPresent()) {
                    sellableLegs++;
                    bidSumMicros += yesBid.get().price().micros();
                    minSellSize = Math.min(minSellSize, yesBid.get().quantity().microContracts());
                }
                // Buyable: an implied YES ask exists, i.e. the NO side has a bid to complement.
                if (yesAsk.isPresent()) {
                    buyableLegs++;
                    askSumMicros += yesAsk.get().micros();
                }
                System.out.println();
            }

            System.out.println("---- exhaustive-bracket sums (pre-fee, top of book only) ----");
            System.out.printf("  markets quoted two-sided : %d of %d%n", quotedBoth,
                    markets.size());
            System.out.printf("  sum of best YES bids     : $%s  (over %d sellable legs)%n",
                    FixedPoint.format(bidSumMicros, 4), sellableLegs);
            System.out.printf("  sum of best YES asks     : $%s  (over %d buyable legs)%n",
                    FixedPoint.format(askSumMicros, 4), buyableLegs);
            System.out.printf("  mean REST fetch          : %.1f ms per book%n",
                    totalFetchMicros / 1000.0 / markets.size());
            System.out.println();

            // The two directions are NOT symmetric, and conflating them invents or hides
            // opportunities.
            //
            // SELL side: short one contract of each bracket in a subset S for sum(bid_i).
            // Exactly one bracket wins overall, so liability is $1.00 if the winner is in S and
            // $0 otherwise. Dropping a leg only lowers the payout, never raises it. So
            // sum(bid) > $1.00 is a genuine violation over ANY subset -- a missing quote does
            // not invalidate it.
            //
            // BUY side: long one contract of each bracket in S for sum(ask_i). The $1.00 payout
            // arrives only if the winner is in S, so the guarantee needs EVERY bracket. With a
            // leg unquoted there is no constraint to test at all.
            if (bidSumMicros > FixedPoint.ONE) {
                System.out.printf("  SELL-SIDE VIOLATION: bids sum to $%s, over $1.00 by $%s.%n",
                        FixedPoint.format(bidSumMicros, 4),
                        FixedPoint.format(bidSumMicros - FixedPoint.ONE, 4));
                System.out.println("    Valid on a subset: shorting fewer legs only reduces the "
                        + "$1.00 liability.");
                System.out.printf("    Gross only. %d legs pay %d fees, each rounded up to a "
                                + "whole cent on the order total, and top-of-book size here is "
                                + "%s contracts.%n",
                        sellableLegs, sellableLegs,
                        minSellSize == Long.MAX_VALUE ? "0"
                                : FixedPoint.format(minSellSize, 2));
            } else {
                System.out.printf("  sell side: bids sum to $%s, under $1.00 by $%s -- no edge%n",
                        FixedPoint.format(bidSumMicros, 4),
                        FixedPoint.format(FixedPoint.ONE - bidSumMicros, 4));
            }

            if (buyableLegs < markets.size()) {
                System.out.printf("  buy side: only %d of %d legs are buyable, so the ask sum is "
                                + "not a constraint at all (an unquoted bracket can still win)%n",
                        buyableLegs, markets.size());
            } else if (askSumMicros < FixedPoint.ONE) {
                System.out.printf("  BUY-SIDE VIOLATION: asks sum to $%s, under $1.00 by $%s "
                                + "(gross, before %d fees)%n",
                        FixedPoint.format(askSumMicros, 4),
                        FixedPoint.format(FixedPoint.ONE - askSumMicros, 4), buyableLegs);
            } else {
                System.out.printf("  buy side: asks sum to $%s, over $1.00 by $%s -- no edge, "
                                + "as expected%n",
                        FixedPoint.format(askSumMicros, 4),
                        FixedPoint.format(askSumMicros - FixedPoint.ONE, 4));
            }
            System.out.println();
            System.out.println("OK: crossed-book invariant held on all " + markets.size()
                    + " books.");
        }
    }

    private static void printTopLevels(Orderbook book) {
        List<PriceLevel> yes = topN(book.bids(Side.YES));
        List<PriceLevel> no = topN(book.bids(Side.NO));
        int rows = Math.max(yes.size(), no.size());
        for (int i = 0; i < rows; i++) {
            System.out.printf("      %-22s | %s%n",
                    i < yes.size() ? "YES " + yes.get(i) : "",
                    i < no.size() ? "NO  " + no.get(i) : "");
        }
    }

    /** Best (highest) levels first — the opposite of wire order. */
    private static List<PriceLevel> topN(List<PriceLevel> levels) {
        return levels.stream()
                .sorted(Comparator.comparing(PriceLevel::price).reversed())
                .limit(DEPTH_ROWS)
                .toList();
    }
}
