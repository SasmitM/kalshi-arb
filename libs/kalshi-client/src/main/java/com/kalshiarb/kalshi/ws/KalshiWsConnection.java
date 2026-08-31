package com.kalshiarb.kalshi.ws;

import com.kalshiarb.common.Backoff;
import com.kalshiarb.common.Clock;
import com.kalshiarb.kalshi.auth.AuthHeaders;
import com.kalshiarb.kalshi.auth.KalshiSigner;
import com.kalshiarb.model.MarketTicker;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

/**
 * Maintains one authenticated WebSocket to Kalshi, with sharded subscriptions, sequence-gap
 * recovery, and reconnection under exponential backoff with jitter.
 *
 * <h2>Host</h2>
 * The WebSocket is <b>not</b> on the REST host. Verified by probing the upgrade request:
 * {@code external-api.kalshi.com/trade-api/ws/v2} returns a backend {@code 404 page not found}
 * on every path variant tried, while {@code api.elections.kalshi.com/trade-api/ws/v2} returns
 * {@code 401 token_authentication_failure} — an endpoint that exists and wants credentials.
 * CLAUDE.md's WS host was carried over from the REST base URL and is wrong.
 *
 * <h2>Auth</h2>
 * The handshake is signed even though every channel used here is public market data. The signed
 * path is {@code /trade-api/ws/v2}, which is <b>not</b> the REST {@code /trade-api/v2} prefix —
 * signing the REST prefix produces a 401 with no other symptom.
 *
 * <h2>Threading</h2>
 * Three threads, with one hard rule:
 * <ol>
 *   <li>The <b>WebSocket callback thread</b> (JDK-owned) timestamps each completed frame and
 *       offers it to a bounded queue. It never parses, never writes, and never blocks. If it
 *       blocked, the connection's automatic Pong replies would stall with it and Kalshi would
 *       drop us.
 *   <li>The <b>pump thread</b> drains the queue, decodes, drives recovery, and calls the
 *       listener. Blocking here is fine.
 *   <li>The <b>watchdog thread</b> checks ping liveness and reconnects when the socket goes
 *       quiet.
 * </ol>
 * On queue overflow the frame is <b>dropped</b> and the drop counter incremented. That is
 * deliberate: a dropped frame produces a sequence gap, and gap recovery already knows how to
 * rebuild from a snapshot. Blocking to avoid the drop would trade a recoverable gap for a dead
 * connection.
 *
 * <h2>Ping/pong</h2>
 * No Pong is sent explicitly. The JDK's own javadoc states that "the WebSocket implementation
 * will automatically send a reciprocal pong when a ping is received", and {@code WebSocketImpl}
 * carries a {@code lastAutomaticPong} field to that effect. Sending our own would put a second,
 * unsolicited Pong on the wire. Ping arrival is instead used as a liveness signal: Kalshi pings
 * roughly every 10s, so a long silence means the connection is dead in a way that produces no
 * close frame.
 */
public final class KalshiWsConnection implements AutoCloseable {

    /** Verified live; see the class javadoc. Not the REST host. */
    public static final String DEFAULT_WS_URL = "wss://api.elections.kalshi.com/trade-api/ws/v2";

    /** The path that gets signed. Note {@code /ws/} — not the REST {@code /trade-api/v2}. */
    public static final String WS_SIGNING_PATH = "/trade-api/ws/v2";

    /**
     * Hand-off queue depth. Deep enough to absorb a burst of snapshots on subscribe, shallow
     * enough that a stalled pump thread is caught by the drop counter rather than by an
     * OutOfMemoryError.
     */
    private static final int QUEUE_CAPACITY = 65_536;

    /**
     * Kalshi pings about every 10s. Three missed intervals is a dead connection; shorter would
     * churn on ordinary jitter.
     */
    private static final Duration PING_TIMEOUT = Duration.ofSeconds(35);

    private static final Duration WATCHDOG_INTERVAL = Duration.ofSeconds(5);
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(20);

    private final String wsUrl;
    private final KalshiSigner signer;
    private final Clock clock;
    private final WsListener listener;
    private final WsMetrics metrics = new WsMetrics();
    private final WsFrameCodec codec = new WsFrameCodec();
    private final Backoff backoff;

    private final List<MarketTicker> watchlist;
    private final List<SubscriptionPlan.Shard> shards;

    private final SequenceTracker sequences = new SequenceTracker();
    private final SnapshotRecovery recovery;

    private final BlockingQueue<RawFrame> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong lastPingNanos = new AtomicLong();
    private final AtomicLong lastFrameNanos = new AtomicLong();
    private final AtomicLong commandIds = new AtomicLong(1_000);

    /** Maps our subscribe command id to the shard it was for, so acks can be attributed. */
    private final Map<Long, SubscriptionPlan.Shard> shardByCommandId = new HashMap<>();

    /** Maps an assigned sid to its shard. Written by the pump thread only. */
    private final Map<Long, SubscriptionPlan.Shard> shardBySid = new HashMap<>();

    private volatile WebSocket socket;
    private volatile String state = "NEW";
    private Thread pumpThread;
    private Thread watchdogThread;
    private HttpClient http;

    public KalshiWsConnection(
            String wsUrl,
            KalshiSigner signer,
            Clock clock,
            List<MarketTicker> watchlist,
            WsListener listener,
            RandomGenerator random) {
        this.wsUrl = wsUrl;
        this.signer = signer;
        this.clock = clock;
        this.listener = listener;
        this.watchlist = List.copyOf(watchlist);
        this.shards = SubscriptionPlan.shardByEventFamily(this.watchlist);
        this.recovery = new SnapshotRecovery(sequences, metrics);
        this.backoff = Backoff.forWebSocket(random);
    }

    public WsMetrics metrics() {
        return metrics;
    }

    public List<SubscriptionPlan.Shard> shards() {
        return shards;
    }

    public String state() {
        return state;
    }

    /** Starts the pump and watchdog threads and connects. Returns immediately. */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("already started");
        }
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        pumpThread = new Thread(this::pumpLoop, "kalshi-ws-pump");
        pumpThread.setDaemon(false);
        pumpThread.start();

        watchdogThread = new Thread(this::watchdogLoop, "kalshi-ws-watchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.start();

        connectWithRetry();
    }

    // ------------------------------------------------------------------ connect

    private void connectWithRetry() {
        while (running.get()) {
            try {
                connectOnce();
                backoff.reset();
                return;
            } catch (Exception e) {
                if (!running.get()) {
                    return;
                }
                long delay = backoff.nextDelayMillis();
                transition("RECONNECT_WAIT",
                        "connect failed (attempt " + backoff.attempts() + "): " + e);
                if (!sleepMillis(delay)) {
                    return;
                }
            }
        }
    }

    private void connectOnce() throws Exception {
        transition("CONNECTING", "dialing " + wsUrl);

        // Sign the WS path, not the REST prefix.
        AuthHeaders headers = signer.sign("GET", WS_SIGNING_PATH);

        WebSocket.Builder builder = http.newWebSocketBuilder()
                .connectTimeout(HANDSHAKE_TIMEOUT)
                .header(AuthHeaders.HEADER_ACCESS_KEY, headers.accessKey())
                .header(AuthHeaders.HEADER_TIMESTAMP, headers.timestamp())
                .header(AuthHeaders.HEADER_SIGNATURE, headers.signature());

        socket = builder.buildAsync(URI.create(wsUrl), new Handler())
                .get(HANDSHAKE_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS);

        long now = clock.nanoTime();
        lastPingNanos.set(now);
        lastFrameNanos.set(now);

        // Every book is invalid across a reconnect: sids are not stable and the sequence
        // baseline is gone. Force every shard back into AWAITING_SNAPSHOT before subscribing.
        recovery.invalidateAll();
        shardBySid.clear();
        shardByCommandId.clear();

        transition("CONNECTED", "handshake accepted");

        for (SubscriptionPlan.Shard shard : shards) {
            long id = commandIds.incrementAndGet();
            SubscriptionPlan.Shard withId =
                    new SubscriptionPlan.Shard(shard.family(), shard.markets(), id);
            shardByCommandId.put(id, withId);
            send(WsCommands.subscribe(id, shard.markets()));
        }
        transition("SUBSCRIBED", shards.size() + " shards, " + watchlist.size() + " markets");
    }

    private void send(String json) {
        WebSocket ws = socket;
        if (ws == null) {
            throw new IllegalStateException("not connected");
        }
        // sendText is asynchronous; the JDK serialises writes internally.
        ws.sendText(json, true);
    }

    // ------------------------------------------------------------------ WS callbacks

    /**
     * Runs on the JDK's WebSocket callback thread. Does the minimum possible: reassemble
     * fragments, timestamp, enqueue.
     */
    private final class Handler implements WebSocket.Listener {

        /**
         * Accumulates message fragments. {@code onText} is called once per fragment with
         * {@code last=false} until the final one, and a JSON frame split across fragments would
         * fail to parse — a subtle bug that only shows up on large snapshots.
         */
        private final StringBuilder partial = new StringBuilder(4096);

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String json = partial.toString();
                partial.setLength(0);

                // Timestamp before anything else touches the frame.
                RawFrame frame = new RawFrame(json, clock.currentTimeMillis(), clock.nanoTime());
                lastFrameNanos.set(frame.receivedAtNanos());
                metrics.frameReceived(frame.sizeBytes());

                // offer(), never put(): blocking here would stall the connection. A drop
                // becomes a sequence gap, and gap recovery handles that.
                if (!queue.offer(frame)) {
                    metrics.frameDropped();
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            // No explicit Pong: the JDK sends a reciprocal one automatically. This is purely a
            // liveness observation.
            lastPingNanos.set(clock.nanoTime());
            metrics.pingReceived();
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            lastPingNanos.set(clock.nanoTime());
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            transition("CLOSED", "server closed: " + statusCode + " " + reason);
            scheduleReconnect("close " + statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            transition("ERROR", String.valueOf(error));
            scheduleReconnect("error " + error);
        }
    }

    // ------------------------------------------------------------------ pump

    private void pumpLoop() {
        while (running.get()) {
            RawFrame frame;
            try {
                frame = queue.poll(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (frame == null) {
                continue;
            }
            handle(frame);
        }
    }

    private void handle(RawFrame frame) {
        listener.onRawFrame(frame);

        WsFrame decoded;
        try {
            decoded = codec.decode(frame.json());
        } catch (RuntimeException e) {
            // A single malformed frame must not kill the connection.
            metrics.decodeFailure();
            listener.onDecodeFailure(frame, e);
            return;
        }

        listener.onFrame(decoded, frame);

        if (decoded instanceof WsFrame.Subscribed sub) {
            SubscriptionPlan.Shard shard = shardByCommandId.get(sub.commandId());
            if (shard != null && sub.sid() >= 0) {
                shardBySid.put(sub.sid(), shard);
                transition(state, "sid " + sub.sid() + " -> " + shard.family()
                        + " (" + shard.size() + " markets)");
            }
            return;
        }

        if (decoded instanceof WsFrame.Sequenced sequenced) {
            SnapshotRecovery.Outcome outcome = recovery.onFrame(sequenced);
            listener.onRecoveryOutcome(outcome);

            // Recovery decides; the connection only performs the I/O it asks for. Critically,
            // neither branch reconnects -- a gap is repaired in place.
            if (outcome instanceof SnapshotRecovery.Outcome.RequestSnapshot request) {
                requestSnapshot(request.sid());
            } else if (outcome instanceof SnapshotRecovery.Outcome.Rebuild rebuild
                    && rebuild.stillGapped()) {
                requestSnapshot(rebuild.snapshot().sid());
            }
        }
    }

    private void requestSnapshot(long sid) {
        try {
            send(WsCommands.getSnapshot(commandIds.incrementAndGet(), sid));
        } catch (RuntimeException e) {
            // If the socket is already gone the watchdog/onClose path will reconnect.
            transition(state, "get_snapshot for sid " + sid + " failed: " + e);
        }
    }

    // ------------------------------------------------------------------ watchdog

    private void watchdogLoop() {
        while (running.get()) {
            if (!sleepMillis(WATCHDOG_INTERVAL.toMillis())) {
                return;
            }
            if (!running.get()) {
                return;
            }
            WebSocket ws = socket;
            if (ws == null) {
                continue;
            }
            long quietNanos = clock.nanoTime() - Math.max(lastPingNanos.get(),
                    lastFrameNanos.get());
            if (quietNanos > PING_TIMEOUT.toNanos()) {
                // No close frame, no error, just silence. Treat as dead.
                transition("STALE", "no ping or frame for "
                        + TimeUnit.NANOSECONDS.toSeconds(quietNanos) + "s");
                scheduleReconnect("ping timeout");
            }
        }
    }

    private final AtomicBoolean reconnecting = new AtomicBoolean();

    private void scheduleReconnect(String reason) {
        if (!running.get()) {
            return;
        }
        // onClose and the watchdog can both fire for the same failure; only one reconnect.
        if (!reconnecting.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                abortSocket();
                metrics.reconnected();
                long delay = backoff.nextDelayMillis();
                transition("RECONNECT_WAIT", reason + "; waiting " + delay + "ms");
                if (sleepMillis(delay) && running.get()) {
                    connectWithRetry();
                }
            } finally {
                reconnecting.set(false);
            }
        }, "kalshi-ws-reconnect");
        t.setDaemon(true);
        t.start();
    }

    private void abortSocket() {
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            // abort(), not sendClose(): the socket is already broken, and a graceful close
            // handshake would just block on a peer that is not answering.
            ws.abort();
        }
    }

    /** Forces a reconnect. Used to exercise the reconnect path deliberately. */
    public void forceReconnect(String reason) {
        transition(state, "forced reconnect: " + reason);
        scheduleReconnect("forced: " + reason);
    }

    // ------------------------------------------------------------------ lifecycle

    private boolean sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void transition(String to, String reason) {
        String from = state;
        state = to;
        listener.onStateChange(from, to, reason);
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        WebSocket ws = socket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "client shutdown");
            } catch (RuntimeException ignored) {
                ws.abort();
            }
        }
        socket = null;
        if (pumpThread != null) {
            pumpThread.interrupt();
        }
        if (watchdogThread != null) {
            watchdogThread.interrupt();
        }
        if (http != null) {
            http.close();
        }
        transition("STOPPED", "closed by caller");
    }

    /** Drains and discards anything queued. For tests. */
    List<RawFrame> drainQueue() {
        List<RawFrame> drained = new ArrayList<>();
        queue.drainTo(drained);
        return drained;
    }
}
