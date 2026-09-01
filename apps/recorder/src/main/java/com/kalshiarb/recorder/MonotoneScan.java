package com.kalshiarb.recorder;

import com.kalshiarb.book.Book;
import com.kalshiarb.common.Clock;
import com.kalshiarb.detector.FeeModel;
import com.kalshiarb.detector.MonotoneThresholdConstraint;
import com.kalshiarb.detector.Opportunity;
import com.kalshiarb.kalshi.rest.KalshiRestClient;
import com.kalshiarb.kalshi.rest.dto.MarketSummary;
import com.kalshiarb.kalshi.rest.dto.Orderbook;
import com.kalshiarb.kalshi.rest.dto.SeriesDetail;
import com.kalshiarb.model.EventTicker;
import com.kalshiarb.model.Quantity;
import com.kalshiarb.model.SeriesTicker;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scans a live nested-threshold series for monotonicity violations.
 *
 * <p>Exists because the weather universe the recorder archives has <b>no</b> nested thresholds:
 * every {@code KXHIGH*} event carries exactly one {@code less} and one {@code greater} market
 * plus four {@code between} brackets, so there is no pair of {@code >=X} contracts to compare.
 * Validating {@link MonotoneThresholdConstraint} against real prices therefore needs a different
 * series — {@code KXBTCD} publishes ~50 nested {@code greater} thresholds per event.
 *
 * <p>Uses REST rather than the WebSocket because this is a one-shot survey, not continuous state.
 * Requests are paced: the read bucket is roughly 200 tokens/second at 10 tokens per call, so
 * ~20 calls/second is the ceiling and this stays well inside it.
 *
 * <p><b>The fee multiplier is read from {@code /series}, not assumed.</b> It is category-specific
 * and higher for crypto than for weather, so hardcoding weather's 1 would understate crypto fees
 * and manufacture opportunities that do not exist.
 *
 * <p>Usage: {@code MonotoneScan [seriesTicker] [maxMarkets] [contracts]}, defaults
 * {@code KXBTCD 30 100}.
 */
public final class MonotoneScan {

    /** ~16 requests/second, comfortably under the read bucket. */
    private static final long PACE_MILLIS = 60;

    private MonotoneScan() {
    }

    public static void main(String[] args) throws Exception {
        SeriesTicker series = SeriesTicker.of(args.length > 0 ? args[0] : "KXBTCD");
        int maxMarkets = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        long contracts = args.length > 2 ? Long.parseLong(args[2]) : 100;

        Clock clock = Clock.system();
        try (KalshiRestClient rest = KalshiRestClient.publicOnly(clock)) {

            SeriesDetail detail = rest.series(series);
            FeeModel fees = FeeModel.fromSeries(detail.feeType(), detail.feeMultiplier());
            System.out.printf("series %s: fee_type=%s fee_multiplier=%s%n",
                    series, detail.feeType(), detail.feeMultiplier());
            System.out.printf("  (weather is x1; this series is x%d)%n", fees.multiplier());

            List<MarketSummary> open = rest.openMarketsInSeries(series, 10);
            Map<EventTicker, List<MarketSummary>> byEvent = new LinkedHashMap<>();
            for (MarketSummary m : open) {
                if (!"greater".equals(m.strikeType()) || m.floorStrike() == null) {
                    continue;
                }
                byEvent.computeIfAbsent(m.event(), k -> new ArrayList<>()).add(m);
            }
            if (byEvent.isEmpty()) {
                System.err.println("no 'greater' threshold markets open in " + series);
                System.exit(1);
            }

            MonotoneThresholdConstraint constraint = new MonotoneThresholdConstraint(fees);
            long totalPairs = 0;
            long violations = 0;
            long profitable = 0;

            for (Map.Entry<EventTicker, List<MarketSummary>> e : byEvent.entrySet()) {
                List<MarketSummary> markets = new ArrayList<>(e.getValue());
                // ORDERING CONTRACT: greater thresholds ascending by strike means price must be
                // non-increasing along the list. Sorted on floor_strike as exact wire text
                // parsed to a comparable number -- never on ticker text, which is off by one
                // from the sub-title.
                markets.sort(Comparator.comparingDouble(m -> Double.parseDouble(m.floorStrike())));
                if (markets.size() > maxMarkets) {
                    markets = markets.subList(0, maxMarkets);
                }

                System.out.printf("%n%s: %d nested 'greater' thresholds%n",
                        e.getKey(), markets.size());

                List<Book> chain = new ArrayList<>(markets.size());
                for (MarketSummary m : markets) {
                    Orderbook ob = rest.orderbook(m.marketTicker());
                    ob.checkNotCrossed();
                    Book book = new Book(m.marketTicker());
                    book.applySnapshot(ob.yesBids(), ob.noBids());
                    chain.add(book);
                    Thread.sleep(PACE_MILLIS);
                }

                int n = chain.size();
                totalPairs += (long) n * (n - 1) / 2;

                List<Opportunity> found =
                        constraint.evaluate(e.getKey(), chain, Quantity.ofContracts(contracts));
                violations += found.size();

                if (found.isEmpty()) {
                    System.out.printf("  no monotonicity violation across %d pairs%n",
                            (long) n * (n - 1) / 2);
                } else {
                    for (Opportunity opp : found) {
                        if (opp.isProfitable()) {
                            profitable++;
                        }
                        System.out.println("  " + opp);
                        for (Opportunity.Leg leg : opp.legs()) {
                            System.out.printf("      %-34s @ %s size %-10s fee %s%n",
                                    leg.market(), leg.price(),
                                    leg.availableSize().toContractString(), leg.fee());
                        }
                    }
                }
            }

            System.out.println();
            System.out.println("==== monotone threshold scan ====");
            System.out.printf("  events scanned   : %d%n", byEvent.size());
            System.out.printf("  pairs checked    : %d%n", totalPairs);
            System.out.printf("  gross violations : %d%n", violations);
            System.out.printf("  SURVIVING FEES   : %d%n", profitable);
            System.out.println();
            System.out.println(violations == 0
                    ? "Result: the nested-threshold relation held on every pair. Monotonicity is "
                            + "priced correctly in this series."
                    : profitable == 0
                            ? "Result: gross violations exist but none survive fees."
                            : "Result: violations survive fees on paper. Verify the book state "
                                    + "and the ordering before believing it.");
        }
    }
}
