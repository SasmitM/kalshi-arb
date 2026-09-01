package com.kalshiarb.recorder;

import com.kalshiarb.common.Clock;
import com.kalshiarb.kalshi.auth.KalshiCredentials;
import com.kalshiarb.kalshi.auth.KalshiSigner;
import com.kalshiarb.kalshi.rest.KalshiRestClient;
import com.kalshiarb.kalshi.rest.dto.MarketSummary;
import com.kalshiarb.kalshi.ws.KalshiWsCluster;
import com.kalshiarb.kalshi.ws.KalshiWsConnection;
import com.kalshiarb.kalshi.ws.RawFrame;
import com.kalshiarb.kalshi.ws.SnapshotRecovery;
import com.kalshiarb.kalshi.ws.SubscriptionPlan;
import com.kalshiarb.kalshi.ws.WsFrame;
import com.kalshiarb.kalshi.ws.WsListener;
import com.kalshiarb.kalshi.ws.WsMetrics;
import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.SeriesTicker;
import com.kalshiarb.persistence.ArchivedFrame;
import com.kalshiarb.persistence.FrameWriter;
import com.kalshiarb.persistence.GzipJsonLinesFrameWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

/**
 * Archives every WebSocket frame to gzipped JSON Lines, indefinitely.
 *
 * <p>Runs unattended. Recorded market data is the scarcest resource in this project — it cannot
 * be backfilled, and every hour not recording is an hour permanently missing from the research
 * dataset — so the design bias throughout is "keep writing" over "be correct about
 * interpretation":
 *
 * <ul>
 *   <li>Frames are archived <b>before</b> decoding, from {@link WsListener#onRawFrame}. A frame
 *       this codebase cannot parse is still recorded, and a decode failure never costs data.
 *   <li>Book state is not maintained here. The recorder's only job is durable capture; task 6
 *       reconstructs books from the archive.
 *   <li>Reconnects, gaps and decode failures are logged and counted, never fatal.
 * </ul>
 *
 * <p><b>Read-only.</b> Public market-data channels only; credentials exist solely because
 * Kalshi authenticates the WebSocket handshake.
 *
 * <p>Usage: {@code RecorderMain [outputDir] [series...]}, defaults
 * {@code ./data/frames KXHIGHNY KXHIGHLAX}.
 */
public final class RecorderMain {

    private static final Duration STATUS_INTERVAL = Duration.ofMinutes(1);

    /**
     * Watchlist is refreshed on this cadence so the recorder rolls onto tomorrow's events
     * without a restart. Weather events settle daily; a recorder pinned to the events that were
     * open when it started would quietly go silent after a day.
     */
    private static final Duration WATCHLIST_REFRESH = Duration.ofHours(6);

    /**
     * How often to ask every shard for a fresh snapshot.
     *
     * <p>These are the verification checkpoints the replay harness needs. Delta application has
     * no natural error signal -- a double-applied or missed delta leaves a book that is quietly
     * wrong while every metric reads green -- so the only way to prove reconstruction is correct
     * is to periodically record what the exchange says the book actually is, and compare. The
     * WebSocket consumes no rate-limit tokens, so the checkpoints are free; the only cost is
     * archive size, and a snapshot is a few KB against ~4500 frames a minute.
     */
    private static final Duration SNAPSHOT_CHECKPOINT = Duration.ofMinutes(5);

    private RecorderMain() {
    }

    public static void main(String[] args) throws Exception {
        Path outputDir = Path.of(args.length > 0 ? args[0] : "data/frames");
        List<String> seriesArgs = args.length > 1
                ? List.of(args).subList(1, args.length)
                : List.of("KXHIGHNY", "KXHIGHLAX");

        Clock clock = Clock.system();
        List<MarketTicker> watchlist = fetchWatchlist(seriesArgs, clock);

        System.out.printf("recorder starting%n  output   : %s%n  series   : %s%n  markets  : %d%n",
                outputDir.toAbsolutePath(), seriesArgs, watchlist.size());

        AtomicLong decodeFailures = new AtomicLong();
        AtomicLong gaps = new AtomicLong();
        AtomicLong latencyCount = new AtomicLong();
        AtomicLong latencySumMillis = new AtomicLong();

        // Not try-with-resources: on SIGTERM the JVM exits without unwinding the try block, so
        // the shutdown hook is the real owner of this resource. Pretending otherwise would mean
        // losing the final buffered frames on every clean stop.
        FrameWriter archive = new GzipJsonLinesFrameWriter(outputDir, "kalshi-frames", clock);
        try {
            WsListener listener = new WsListener() {
                @Override
                public void onRawFrame(RawFrame frame) {
                    try {
                        archive.write(new ArchivedFrame(
                                frame.family(), frame.json(),
                                frame.receivedAtMillis(), frame.receivedAtNanos()));
                    } catch (IOException e) {
                        // Losing the archive is the one failure worth shouting about, but it
                        // still must not kill the connection: the disk may recover, and a
                        // running socket with a broken writer is better than neither.
                        System.err.printf("[%s] ARCHIVE WRITE FAILED: %s%n",
                                Instant.now(), e);
                    }
                }

                @Override
                public void onFrame(WsFrame frame, RawFrame raw) {
                    // Decoding here is purely for the liveness readout. The archive already
                    // holds the frame regardless of whether this succeeds.
                    if (frame instanceof WsFrame.Delta d && d.hasExchangeTimestamp()) {
                        latencyCount.incrementAndGet();
                        latencySumMillis.addAndGet(
                                raw.receivedAtMillis() - d.exchangeTimestampMillis());
                    }
                }

                @Override
                public void onRecoveryOutcome(SnapshotRecovery.Outcome outcome) {
                    if (outcome instanceof SnapshotRecovery.Outcome.RequestSnapshot r) {
                        gaps.incrementAndGet();
                        System.out.printf("[%s] gap sid=%d expected=%d got=%d missing=%d "
                                        + "-> get_snapshot%n",
                                Instant.now(), r.sid(), r.expectedSeq(), r.observedSeq(),
                                r.missingCount());
                    } else if (outcome instanceof SnapshotRecovery.Outcome.Rebuild rb
                            && rb.stillGapped()) {
                        System.out.printf("[%s] rebuild sid=%d still gapped, requesting "
                                        + "another snapshot%n",
                                Instant.now(), rb.snapshot().sid());
                    }
                }

                @Override
                public void onDecodeFailure(RawFrame frame, Exception cause) {
                    // Archived already, so this is a note, not a loss.
                    long n = decodeFailures.incrementAndGet();
                    if (n <= 20 || n % 1000 == 0) {
                        System.err.printf("[%s] decode failure #%d (frame IS archived): %s%n",
                                Instant.now(), n, cause.getMessage());
                    }
                }

                @Override
                public void onStateChange(String from, String to, String reason) {
                    System.out.printf("[%s] %-15s -> %-15s %s%n", Instant.now(), from, to,
                            reason);
                }
            };

            KalshiSigner signer =
                    KalshiSigner.from(KalshiCredentials.fromEnvironment(), clock);

            KalshiWsCluster cluster = new KalshiWsCluster(
                    KalshiWsConnection.DEFAULT_WS_URL, signer, clock, watchlist, listener,
                    RandomGenerator.getDefault());

            for (SubscriptionPlan.Shard shard : cluster.shards()) {
                System.out.printf("  shard    : %s (%d markets)%n",
                        shard.family(), shard.size());
            }

            // Flush and close the archive on Ctrl-C or SIGTERM. Without this the final buffered
            // frames are lost on every shutdown, which over many restarts is a real hole.
            Thread shutdown = new Thread(() -> {
                System.out.printf("%n[%s] shutting down, flushing archive...%n", Instant.now());
                try {
                    cluster.close();
                } catch (RuntimeException e) {
                    System.err.println("cluster close failed: " + e);
                }
                flushAndClose(archive);
            }, "recorder-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);

            cluster.start();
            cluster.describe().forEach(l -> System.out.println("  " + l));

            runForever(cluster, archive, clock, seriesArgs, decodeFailures, gaps,
                    latencyCount, latencySumMillis);
        } finally {
            // Reached on an unexpected throw; the shutdown hook covers signals. Both paths are
            // idempotent.
            flushAndClose(archive);
        }
    }

    /** Idempotent, and never throws — a failure here must not mask the original error. */
    private static void flushAndClose(FrameWriter archive) {
        try {
            archive.flush();
            archive.close();
            System.out.printf("[%s] archive closed: %d frames%n",
                    Instant.now(), archive.framesWritten());
        } catch (IOException e) {
            System.err.println("archive close failed: " + e);
        }
    }

    private static void runForever(
            KalshiWsCluster cluster,
            FrameWriter archive,
            Clock clock,
            List<String> seriesArgs,
            AtomicLong decodeFailures,
            AtomicLong gaps,
            AtomicLong latencyCount,
            AtomicLong latencySumMillis) throws InterruptedException {

        WsMetrics m = cluster.metrics();
        long lastStatus = clock.nanoTime();
        long lastRefreshCheck = clock.nanoTime();
        long lastCheckpoint = clock.nanoTime();
        List<MarketTicker> currentWatchlist = cluster.shards().stream()
                .flatMap(s -> s.markets().stream())
                .toList();

        while (true) {
            Thread.sleep(1_000);

            long now = clock.nanoTime();
            if (now - lastStatus >= STATUS_INTERVAL.toNanos()) {
                lastStatus = now;
                long lat = latencyCount.get();
                System.out.printf("[%s] archived=%d (%.1f MB raw) | %s | decodeFail=%d gaps=%d"
                                + " | meanLatency=%s%n",
                        Instant.now(),
                        archive.framesWritten(),
                        archive.bytesWritten() / 1_048_576.0,
                        m,
                        decodeFailures.get(),
                        gaps.get(),
                        lat == 0 ? "n/a"
                                : String.format("%.1f ms", latencySumMillis.get() / (double) lat));
                if (archive instanceof GzipJsonLinesFrameWriter g) {
                    System.out.printf("           file: %s%n", g.currentPath());
                }
                cluster.describe().forEach(l -> System.out.println("           " + l));
            }

            if (now - lastCheckpoint >= SNAPSHOT_CHECKPOINT.toNanos()) {
                lastCheckpoint = now;
                int asked = cluster.requestSnapshotAll();
                System.out.printf("[%s] checkpoint: requested snapshots on %d shards%n",
                        Instant.now(), asked);
            }

            // Roll onto newly opened events without a restart.
            if (now - lastRefreshCheck >= WATCHLIST_REFRESH.toNanos()) {
                lastRefreshCheck = now;
                try {
                    List<MarketTicker> refreshed = fetchWatchlist(seriesArgs, clock);
                    if (!refreshed.isEmpty() && !refreshed.equals(currentWatchlist)) {
                        System.out.printf("[%s] watchlist changed (%d -> %d markets). Restart "
                                        + "the recorder to pick up the new event families.%n",
                                Instant.now(), currentWatchlist.size(), refreshed.size());
                        currentWatchlist = refreshed;
                    }
                } catch (RuntimeException e) {
                    // A REST hiccup must never take down an archiving process.
                    System.err.printf("[%s] watchlist refresh failed, continuing: %s%n",
                            Instant.now(), e);
                }
            }
        }
    }

    /**
     * Builds the watchlist from the public REST API.
     *
     * <p>Whole event families only. CLAUDE.md asks for 6-10 markets, which corresponds to one or
     * two families — a family cannot be partially subscribed, because the exhaustive-bracket
     * constraint needs every bracket in the set to mean anything. Two series therefore give four
     * families and ~24 markets, which a live run handled at ~36 frames/s with zero drops.
     */
    private static List<MarketTicker> fetchWatchlist(List<String> seriesArgs, Clock clock) {
        List<MarketTicker> watchlist = new ArrayList<>();
        try (KalshiRestClient rest = KalshiRestClient.publicOnly(clock)) {
            for (String s : seriesArgs) {
                for (MarketSummary market :
                        rest.openMarketsInSeries(SeriesTicker.of(s), 10)) {
                    if (!market.ticker().startsWith("KXHIGH")) {
                        continue;
                    }
                    watchlist.add(market.marketTicker());
                }
            }
        }
        if (watchlist.isEmpty()) {
            throw new UncheckedIOException(
                    new IOException("no open KXHIGH markets found for " + seriesArgs));
        }
        return watchlist;
    }
}
