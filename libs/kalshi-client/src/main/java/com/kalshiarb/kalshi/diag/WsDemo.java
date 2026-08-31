package com.kalshiarb.kalshi.diag;

import com.kalshiarb.common.Clock;
import com.kalshiarb.kalshi.auth.KalshiCredentials;
import com.kalshiarb.kalshi.auth.KalshiSigner;
import com.kalshiarb.kalshi.rest.KalshiRestClient;
import com.kalshiarb.kalshi.rest.dto.MarketSummary;
import com.kalshiarb.kalshi.ws.KalshiWsConnection;
import com.kalshiarb.kalshi.ws.RawFrame;
import com.kalshiarb.kalshi.ws.SnapshotRecovery;
import com.kalshiarb.kalshi.ws.SubscriptionPlan;
import com.kalshiarb.kalshi.ws.WsFrame;
import com.kalshiarb.kalshi.ws.WsListener;
import com.kalshiarb.kalshi.ws.WsMetrics;
import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.SeriesTicker;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

/**
 * Holds a live authenticated WebSocket, sharded by event family, and reports gaps, recoveries and
 * reconnects. This is the runnable form of task 4's acceptance criterion.
 *
 * <p>Builds its watchlist from the public REST API — the open markets of the requested weather
 * series — so the tickers are always current rather than hardcoded to events that have settled.
 *
 * <p>Forces one reconnect partway through, because the reconnect path is the half of the
 * connection manager that ordinary uptime never exercises. A clean run shows books being
 * re-subscribed and every shard returning to STREAMING afterwards.
 *
 * <p>Read-only. Subscribes to public book channels; the only reason credentials are needed at
 * all is that Kalshi authenticates the handshake itself.
 *
 * <p>Usage: {@code WsDemo [minutes] [forceReconnectAfterMinutes] [series...]}, defaults
 * {@code 30 10 KXHIGHNY KXHIGHLAX}.
 */
public final class WsDemo {

    private WsDemo() {
    }

    public static void main(String[] args) throws Exception {
        int minutes = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        int reconnectAfter = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        List<String> seriesArgs = args.length > 2
                ? List.of(args).subList(2, args.length)
                : List.of("KXHIGHNY", "KXHIGHLAX");

        Clock clock = Clock.system();

        List<MarketTicker> watchlist = new ArrayList<>();
        try (KalshiRestClient rest = KalshiRestClient.publicOnly(clock)) {
            for (String s : seriesArgs) {
                List<MarketSummary> open =
                        rest.openMarketsInSeries(SeriesTicker.of(s), 10);
                for (MarketSummary m : open) {
                    watchlist.add(m.marketTicker());
                }
                System.out.printf("watchlist: %s -> %d open markets%n", s, open.size());
            }
        }
        if (watchlist.isEmpty()) {
            System.err.println("no open markets found for " + seriesArgs);
            System.exit(1);
        }

        KalshiSigner signer = KalshiSigner.from(KalshiCredentials.fromEnvironment(), clock);

        AtomicLong applied = new AtomicLong();
        AtomicLong buffered = new AtomicLong();
        AtomicLong rebuilds = new AtomicLong();
        AtomicLong snapshots = new AtomicLong();

        // Protocol reconnaissance. The WS field names are the one part of this client that
        // could not be written against a captured payload, so the first frame of each type is
        // dumped in full. That is how the yes_dollars_fp discrepancy was found, and it is still
        // open whether `ok` frames occupy sequence numbers and whether a second sid ever
        // appears. Bounded per type so a 30-minute run does not drown in raw JSON.
        java.util.Set<String> dumpedTypes = java.util.concurrent.ConcurrentHashMap.newKeySet();
        AtomicLong deltasDumped = new AtomicLong();
        java.util.Set<Long> seenSids = java.util.concurrent.ConcurrentHashMap.newKeySet();

        // If Kalshi assigns one sid per subscribe command, this many distinct sids should show
        // up. A live run produced exactly one for four commands, which would mean sharding on a
        // single connection buys no isolation at all. This makes the answer unmissable.
        int expectedShards = SubscriptionPlan.shardByEventFamily(watchlist).size();

        // Delivery latency from Kalshi's ts_ms against our local receive time. This is the
        // headline number the project is meant to produce, so it gets reported even from a
        // two-minute diagnostic run.
        AtomicLong latencyCount = new AtomicLong();
        AtomicLong latencySumMillis = new AtomicLong();
        AtomicLong latencyMinMillis = new AtomicLong(Long.MAX_VALUE);
        AtomicLong latencyMaxMillis = new AtomicLong(Long.MIN_VALUE);

        WsListener listener = new WsListener() {
            @Override
            public void onStateChange(String from, String to, String reason) {
                System.out.printf("[%s] %-15s -> %-15s %s%n", stamp(), from, to, reason);
            }

            @Override
            public void onFrame(WsFrame frame, RawFrame raw) {
                // One full dump per frame type, so the wire format is recorded rather than
                // inferred from whether decoding happened to succeed.
                if (dumpedTypes.add(frame.type())) {
                    System.out.printf("%n[%s] ==== first %s frame, raw ====%n%s%n%n",
                            stamp(), frame.type(), raw.json());
                }

                switch (frame) {
                    case WsFrame.Snapshot snap -> {
                        snapshots.incrementAndGet();
                        noteSid(snap.sid());
                        System.out.printf("[%s] snapshot sid=%d seq=%d %s (%d yes / %d no)%n",
                                stamp(), snap.sid(), snap.seq(), snap.ticker(),
                                snap.yesBids().size(), snap.noBids().size());
                    }
                    case WsFrame.Delta d -> {
                        noteSid(d.sid());
                        // Real delivery latency: exchange send time vs local receive time.
                        // Carries clock skew between the two machines, so it is worth reading
                        // as a distribution rather than a per-frame truth.
                        if (d.hasExchangeTimestamp()) {
                            long lag = raw.receivedAtMillis() - d.exchangeTimestampMillis();
                            latencyCount.incrementAndGet();
                            latencySumMillis.addAndGet(lag);
                            latencyMinMillis.accumulateAndGet(lag, Math::min);
                            latencyMaxMillis.accumulateAndGet(lag, Math::max);
                        }
                        // A few deltas in full: needed to tell whether the fixed-point or the
                        // legacy integer branch is being taken. The legacy branch is whole
                        // cents and would be silently losing sub-penny prices.
                        if (deltasDumped.incrementAndGet() <= 3) {
                            System.out.printf("[%s] delta raw: %s%n", stamp(), raw.json());
                            System.out.printf("[%s]   decoded: sid=%d seq=%d %s %s %s %s%n",
                                    stamp(), d.sid(), d.seq(), d.ticker(), d.side(), d.price(),
                                    d.deltaMicroContracts());
                        }
                    }
                    case WsFrame.Ok ok -> {
                        noteSid(ok.sid());
                        System.out.printf("[%s] ok id=%d sid=%d seq=%s%n",
                                stamp(), ok.commandId(), ok.sid(),
                                ok.hasSeq() ? String.valueOf(ok.seq())
                                        : "ABSENT (not in sequence stream)");
                    }
                    case WsFrame.Subscribed sub -> noteSid(sub.sid());
                    case WsFrame.ErrorFrame err -> System.out.printf(
                            "[%s] ERROR id=%d code=%s: %s%n",
                            stamp(), err.commandId(), err.code(), err.message());
                    case WsFrame.Unhandled u -> System.out.printf(
                            "[%s] unhandled frame type: %s%n", stamp(), u.type());
                }
            }

            private void noteSid(long sid) {
                if (sid >= 0 && seenSids.add(sid)) {
                    System.out.printf("[%s] >>> new sid observed: %d "
                                    + "(distinct sids: %d, shards subscribed: %d)%n",
                            stamp(), sid, seenSids.size(), expectedShards);
                }
            }

            @Override
            public void onRecoveryOutcome(SnapshotRecovery.Outcome outcome) {
                switch (outcome) {
                    case SnapshotRecovery.Outcome.Apply a -> applied.incrementAndGet();
                    case SnapshotRecovery.Outcome.Buffered b -> buffered.incrementAndGet();
                    case SnapshotRecovery.Outcome.RequestSnapshot r -> System.out.printf(
                            "[%s] GAP sid=%d expected=%d got=%d missing=%d discarded=%d "
                                    + "-> get_snapshot (no reconnect)%n",
                            stamp(), r.sid(), r.expectedSeq(), r.observedSeq(),
                            r.missingCount(), r.discarded());
                    case SnapshotRecovery.Outcome.Rebuild rb -> {
                        rebuilds.incrementAndGet();
                        System.out.printf(
                                "[%s] REBUILD sid=%d snapshotSeq=%d replay=%d "
                                        + "discardedStale=%d stillGapped=%s abandoned=%d%n",
                                stamp(), rb.snapshot().sid(), rb.snapshot().seq(),
                                rb.replay().size(), rb.discardedStale(),
                                rb.stillGapped(), rb.abandonedAfterGap());
                    }
                    case SnapshotRecovery.Outcome.SequenceOnly so -> System.out.printf(
                            "[%s] seq-only sid=%d seq=%d (%s) -- counter advanced, no book "
                                    + "change%n",
                            stamp(), so.sid(), so.seq(), so.frameType());
                    case SnapshotRecovery.Outcome.Ignored ig -> System.out.printf(
                            "[%s] ignored sid=%d seq=%d: %s%n",
                            stamp(), ig.sid(), ig.seq(), ig.reason());
                }
            }

            @Override
            public void onDecodeFailure(RawFrame frame, Exception cause) {
                System.out.printf("[%s] DECODE FAILURE: %s%n  frame: %s%n",
                        stamp(), cause.getMessage(),
                        frame.json().length() > 200
                                ? frame.json().substring(0, 200) + "..." : frame.json());
            }
        };

        try (KalshiWsConnection ws = new KalshiWsConnection(
                KalshiWsConnection.DEFAULT_WS_URL, signer, clock, watchlist, listener,
                RandomGenerator.getDefault())) {

            System.out.printf("connecting to %s%n", KalshiWsConnection.DEFAULT_WS_URL);
            System.out.printf("signing path  %s%n", KalshiWsConnection.WS_SIGNING_PATH);
            ws.start();
            for (SubscriptionPlan.Shard shard : ws.shards()) {
                System.out.printf("  shard cmd=%d %s (%d markets)%n",
                        shard.commandId(), shard.family(), shard.size());
            }

            WsMetrics m = ws.metrics();
            long deadline = System.nanoTime() + Duration.ofMinutes(minutes).toNanos();
            long reconnectAt = System.nanoTime() + Duration.ofMinutes(reconnectAfter).toNanos();
            boolean forced = false;
            long lastReport = 0;

            while (System.nanoTime() < deadline) {
                Thread.sleep(1_000);

                if (!forced && System.nanoTime() > reconnectAt) {
                    forced = true;
                    System.out.printf("%n[%s] ==== forcing a reconnect to exercise recovery "
                            + "====%n%n", stamp());
                    ws.forceReconnect("scheduled drill");
                }

                if (System.nanoTime() - lastReport > Duration.ofSeconds(30).toNanos()) {
                    lastReport = System.nanoTime();
                    System.out.printf("[%s] %s | applied=%d buffered=%d rebuilds=%d state=%s%n",
                            stamp(), m, applied.get(), buffered.get(), rebuilds.get(),
                            ws.state());
                }
            }

            System.out.println();
            System.out.println("==== final ====");
            System.out.println("  " + m);
            System.out.printf("  applied=%d buffered=%d rebuilds=%d snapshots=%d%n",
                    applied.get(), buffered.get(), rebuilds.get(), snapshots.get());
            System.out.printf("  reconnects=%d (expected >= 1 from the drill)%n", m.reconnects());
            if (latencyCount.get() > 0) {
                System.out.printf("  delivery latency over %d deltas: mean %.1f ms, "
                                + "min %d ms, max %d ms  (exchange ts_ms -> local receive)%n",
                        latencyCount.get(),
                        latencySumMillis.get() / (double) latencyCount.get(),
                        latencyMinMillis.get(), latencyMaxMillis.get());
                System.out.println("    includes clock skew between Kalshi's host and this "
                        + "machine; a negative min means our clock runs behind theirs.");
            } else {
                System.out.println("  no exchange timestamps seen -- latency not measurable "
                        + "from this run");
            }
            System.out.printf("  distinct sids=%d for %d subscribed shards -> %s%n",
                    seenSids.size(), expectedShards,
                    seenSids.size() < expectedShards
                            ? "SHARDING GIVES NO ISOLATION on one connection; a gap invalidates "
                                    + "every market. Needs one connection per shard."
                            : "one sid per shard, blast radius bounded as intended");
            if (m.decodeFailures() > 0) {
                System.out.printf("  WARNING: %d decode failures -- a wire field name is still "
                        + "wrong; do NOT start the recorder until this is zero%n",
                        m.decodeFailures());
            }
            if (m.framesDropped() > 0) {
                System.out.printf("  WARNING: %d frames dropped -- the pump thread could not "
                        + "keep up and gaps were recovered instead%n", m.framesDropped());
            }
            if (m.pingsReceived() == 0) {
                System.out.println("  note: zero pings received. CLAUDE.md's ~10s ping interval "
                        + "does not hold; the staleness watchdog keys off frames too, so a "
                        + "quiet market would otherwise false-trigger a reconnect.");
            }
        }
    }

    private static String stamp() {
        return Instant.now().toString();
    }
}
