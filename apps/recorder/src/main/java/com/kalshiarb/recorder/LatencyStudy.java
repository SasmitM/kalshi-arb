package com.kalshiarb.recorder;

import com.kalshiarb.book.Book;
import com.kalshiarb.detector.ExhaustiveSumConstraint;
import com.kalshiarb.detector.FeeModel;
import com.kalshiarb.detector.Opportunity;
import com.kalshiarb.kalshi.ws.WsFrame;
import com.kalshiarb.kalshi.ws.WsFrameCodec;
import com.kalshiarb.model.EventTicker;
import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.Money;
import com.kalshiarb.model.Quantity;
import com.kalshiarb.persistence.ArchivedFrame;
import com.kalshiarb.persistence.GzipJsonLinesFrameReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Measures how long constraint violations actually last, and therefore what latency could
 * capture them.
 *
 * <p>This is the project's stated deliverable: <em>what fraction of detected opportunities
 * survive at 10ms / 50ms / 200ms / 1s of latency</em>. Note what it is not — CLAUDE.md defers the
 * replay <b>engine</b> (a simulator that re-runs strategies against a synthetic clock). This is an
 * analysis over recorded frames, which needs no simulator: the archive already holds the
 * exchange's {@code ts_ms} and our local receive time on every frame.
 *
 * <h2>Method</h2>
 * A violation is tracked as an <b>episode</b>: the contiguous span during which it exists,
 * bounded by the receive timestamps of the frames that opened and closed it. An opportunity
 * observed at time T and acted on with latency L is only capturable if the episode outlives L.
 * So the survival fraction at L is simply the fraction of episodes whose duration exceeds L.
 *
 * <p>Durations use the local receive clock rather than the exchange's {@code ts_ms}, because the
 * question is about <em>our</em> reaction window. The delivery latency is reported separately, as
 * the floor beneath any achievable reaction: an episode shorter than one-way delivery was never
 * observable in time, whatever the strategy.
 *
 * <p>Usage: {@code LatencyStudy [archiveDirOrFile] [contracts]}, defaults {@code data/frames 1}.
 * Size 1 is the default deliberately — it is the most generous case, since larger sizes are
 * capped by depth and priced worse by the depth walk.
 */
public final class LatencyStudy {

    /** The latency budgets CLAUDE.md names. */
    private static final long[] BUDGETS_MILLIS = {0, 10, 50, 200, 1_000};

    private final WsFrameCodec codec = new WsFrameCodec();
    private final ExhaustiveSumConstraint constraint;
    private final Quantity size;

    private final Map<MarketTicker, Book> books = new LinkedHashMap<>();
    private final Map<EventTicker, List<MarketTicker>> family = new LinkedHashMap<>();
    private final Map<EventTicker, Long> expectedSeq = new HashMap<>();

    /** Open episodes, keyed by family + direction. */
    private final Map<String, Episode> open = new HashMap<>();

    private final List<Episode> closed = new ArrayList<>();
    private final List<Long> deliveryLatencies = new ArrayList<>();

    private long framesRead;
    private long evaluations;

    /** One violation's lifetime. */
    private static final class Episode {
        final String key;
        final long startMillis;
        long lastMillis;
        long bestGrossMicros;
        long bestNetMicros = Long.MIN_VALUE;
        int observations;

        Episode(String key, long startMillis) {
            this.key = key;
            this.startMillis = startMillis;
            this.lastMillis = startMillis;
        }

        long durationMillis() {
            return lastMillis - startMillis;
        }
    }

    LatencyStudy(FeeModel fees, Quantity size) {
        this.constraint = new ExhaustiveSumConstraint(fees);
        this.size = size;
    }

    public static void main(String[] args) throws IOException {
        Path target = Path.of(args.length > 0 ? args[0] : "data/frames");
        long contracts = args.length > 1 ? Long.parseLong(args[1]) : 1;

        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(target)) {
            try (Stream<Path> s = Files.list(target)) {
                s.filter(p -> p.getFileName().toString().endsWith(".jsonl.gz"))
                        .sorted().forEach(files::add);
            }
        } else {
            files.add(target);
        }
        if (files.isEmpty()) {
            System.err.println("no .jsonl.gz archives under " + target.toAbsolutePath());
            System.exit(1);
        }

        LatencyStudy study =
                new LatencyStudy(FeeModel.weather(), Quantity.ofContracts(contracts));
        for (Path file : files) {
            System.out.printf("analysing %s (%.2f MB) at %d contracts%n",
                    file, Files.size(file) / 1_048_576.0, contracts);
            study.run(file);
        }
        study.report();
    }

    void run(Path file) throws IOException {
        try (GzipJsonLinesFrameReader reader = new GzipJsonLinesFrameReader(file)) {
            reader.forEach(this::onFrame);
        }
    }

    private void onFrame(ArchivedFrame archived) {
        framesRead++;
        WsFrame frame;
        try {
            frame = codec.decode(archived.json());
        } catch (RuntimeException e) {
            return;
        }
        EventTicker fam = archived.family();

        if (frame instanceof WsFrame.Sequenced s) {
            Long expected = expectedSeq.put(fam, s.seq() + 1);
            if (expected != null && s.seq() < expected) {
                for (MarketTicker m : family.getOrDefault(fam, List.of())) {
                    books.remove(m);
                }
                family.remove(fam);
            }
        }

        switch (frame) {
            case WsFrame.Snapshot snap -> {
                books.computeIfAbsent(snap.ticker(), Book::new)
                        .applySnapshot(snap.yesBids(), snap.noBids());
                track(fam, snap.ticker());
                recordDelivery(archived, snap.exchangeTimestampMillis());
                evaluate(fam, archived.receivedAtMillis());
            }
            case WsFrame.Delta d -> {
                Book book = books.get(d.ticker());
                if (book == null) {
                    return;
                }
                try {
                    book.applyDelta(d.side(), d.price(), d.deltaMicroContracts());
                } catch (RuntimeException e) {
                    return;
                }
                track(fam, d.ticker());
                recordDelivery(archived, d.exchangeTimestampMillis());
                evaluate(fam, archived.receivedAtMillis());
            }
            default -> {
                // control frames carry no book state
            }
        }
    }

    private void recordDelivery(ArchivedFrame archived, long exchangeMillis) {
        if (exchangeMillis >= 0) {
            deliveryLatencies.add(archived.receivedAtMillis() - exchangeMillis);
        }
    }

    private void track(EventTicker fam, MarketTicker market) {
        List<MarketTicker> markets = family.computeIfAbsent(fam, k -> new ArrayList<>());
        if (!markets.contains(market)) {
            markets.add(market);
        }
    }

    private void evaluate(EventTicker fam, long nowMillis) {
        List<MarketTicker> markets = family.get(fam);
        if (markets == null || markets.size() < 6) {
            return;
        }
        List<Book> brackets = new ArrayList<>(markets.size());
        for (MarketTicker m : markets) {
            Book b = books.get(m);
            if (b == null) {
                return;
            }
            brackets.add(b);
        }
        evaluations++;

        // Which directions are violating right now.
        Map<String, Opportunity> present = new HashMap<>();
        for (Opportunity opp : constraint.evaluate(fam, brackets, size)) {
            present.put(fam.value() + "/" + opp.direction(), opp);
        }

        // Extend or open episodes.
        for (Map.Entry<String, Opportunity> e : present.entrySet()) {
            Episode ep = open.computeIfAbsent(e.getKey(), k -> new Episode(k, nowMillis));
            ep.lastMillis = nowMillis;
            ep.observations++;
            ep.bestGrossMicros = Math.max(ep.bestGrossMicros, e.getValue().grossEdge().micros());
            ep.bestNetMicros = Math.max(ep.bestNetMicros, e.getValue().netEdge().micros());
        }
        // Close episodes for this family that are no longer violating.
        open.entrySet().removeIf(entry -> {
            if (!entry.getKey().startsWith(fam.value() + "/") || present.containsKey(
                    entry.getKey())) {
                return false;
            }
            closed.add(entry.getValue());
            return true;
        });
    }

    void report() {
        // Anything still open at the end of the archive is censored, not zero-length; include it
        // with the duration observed so far rather than discarding it.
        List<Episode> all = new ArrayList<>(closed);
        all.addAll(open.values());

        System.out.println();
        System.out.println("==== delivery latency (exchange ts_ms -> local receive) ====");
        if (deliveryLatencies.isEmpty()) {
            System.out.println("  no exchange timestamps found");
        } else {
            List<Long> l = new ArrayList<>(deliveryLatencies);
            l.sort(null);
            System.out.printf("  n=%d  p50=%dms  p90=%dms  p99=%dms  min=%dms  max=%dms%n",
                    l.size(), pct(l, 50), pct(l, 90), pct(l, 99), l.get(0), l.get(l.size() - 1));
            System.out.println("  This is the FLOOR on any reaction: an episode shorter than "
                    + "one-way delivery");
            System.out.println("  was never observable in time, whatever the strategy. Carries "
                    + "clock skew, so");
            System.out.println("  read it as a distribution.");
        }

        System.out.println();
        System.out.println("==== violation episode lifetimes ====");
        System.out.printf("  frames analysed  : %d%n", framesRead);
        System.out.printf("  states evaluated : %d%n", evaluations);
        System.out.printf("  episodes         : %d%n", all.size());
        if (all.isEmpty()) {
            System.out.println("  no violations to measure");
            return;
        }
        List<Long> durations = new ArrayList<>(all.size());
        for (Episode e : all) {
            durations.add(e.durationMillis());
        }
        durations.sort(null);
        System.out.printf("  duration ms      : p50=%d p90=%d p99=%d max=%d%n",
                pct(durations, 50), pct(durations, 90), pct(durations, 99),
                durations.get(durations.size() - 1));

        System.out.println();
        System.out.println("==== survival by latency budget ====");
        System.out.println("  budget   episodes surviving   of which clear fees");
        for (long budget : BUDGETS_MILLIS) {
            long surviving = 0;
            long profitable = 0;
            for (Episode e : all) {
                if (e.durationMillis() > budget) {
                    surviving++;
                    if (e.bestNetMicros > 0) {
                        profitable++;
                    }
                }
            }
            System.out.printf("  %5dms   %6d (%5.1f%%)        %6d%n",
                    budget, surviving, 100.0 * surviving / all.size(), profitable);
        }

        long everProfitable = all.stream().filter(e -> e.bestNetMicros > 0).count();
        long bestGross = all.stream().mapToLong(e -> e.bestGrossMicros).max().orElse(0);

        System.out.println();
        System.out.printf("  best gross edge seen in any episode : %s%n", new Money(bestGross));
        System.out.printf("  episodes ever clearing fees         : %d of %d%n",
                everProfitable, all.size());
        System.out.println();
        if (everProfitable == 0) {
            System.out.println("Result: no episode cleared fees at any latency, including 0ms. "
                    + "Latency is not the");
            System.out.println("binding constraint here -- fees are. A zero-latency, "
                    + "colocated system would still");
            System.out.println("lose money on every violation in this dataset.");
        } else {
            System.out.printf("Result: %d episodes cleared fees; see the survival table for how "
                    + "many outlive each%n", everProfitable);
            System.out.println("latency budget.");
        }
    }

    private static long pct(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.min(sorted.size() - 1L, (long) (sorted.size() * (p / 100.0)));
        return sorted.get(idx);
    }
}
