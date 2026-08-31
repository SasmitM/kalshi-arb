package com.kalshiarb.kalshi.ws;

import com.kalshiarb.common.Backoff;
import com.kalshiarb.common.Clock;
import com.kalshiarb.kalshi.auth.AuthHeaders;
import com.kalshiarb.kalshi.auth.KalshiSigner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

/**
 * One authenticated WebSocket carrying exactly <b>one</b> shard — the markets of a single event
 * family — with its own sequence tracking, gap recovery and reconnect schedule.
 *
 * <h2>Why one shard per connection</h2>
 * Because Kalshi assigns <b>one {@code sid} per channel per connection</b>, not one per
 * subscribe command. Verified live: four {@code subscribe} commands for four event families on a
 * single socket all came back on {@code sid=1}, with {@code seq} running continuously 1→27
 * across every family, answered by one {@code subscribed} and three {@code ok}. Sending separate
 * subscribe commands therefore buys <b>no isolation whatsoever</b> — a single dropped frame
 * invalidates every market on the socket.
 *
 * <p>Since {@code seq} is per-{@code sid} and there is only one {@code sid} per connection, the
 * only way to bound a gap's blast radius to one constraint group is a separate connection per
 * group. {@link KalshiWsCluster} owns the fan-out; this class stays deliberately single-shard.
 *
 * <p>A real benefit falls out: each connection has its own pump thread, its own
 * {@link SequenceTracker} and its own {@link SnapshotRecovery}, so a shard's books are touched
 * by exactly one thread. Single-writer-per-shard stops being an aspiration and becomes a
 * structural property, which is what keeps locks out of the book layer.
 *
 * <h2>Host and auth</h2>
 * The WebSocket is <b>not</b> on the REST host: {@code external-api.kalshi.com/trade-api/ws/v2}
 * returns a backend {@code 404 page not found}, while
 * {@code api.elections.kalshi.com/trade-api/ws/v2} returns {@code 401} — an endpoint that exists
 * and wants credentials. The handshake is signed even though every channel here is public market
 * data, and the signed path is {@code /trade-api/ws/v2}, <b>not</b> the REST
 * {@code /trade-api/v2} prefix.
 *
 * <h2>Threading</h2>
 * Three threads per connection, with one hard rule:
 * <ol>
 *   <li>The <b>WebSocket callback thread</b> (JDK-owned) reassembles fragments, timestamps the
 *       frame, and offers it to a bounded queue. It never parses, never writes, never blocks —
 *       blocking it would stall the connection's automatic Pong replies too.
 *   <li>The <b>pump thread</b> drains the queue, decodes, drives recovery, calls the listener.
 *   <li>The <b>watchdog thread</b> reconnects when the socket goes silent.
 * </ol>
 * On queue overflow the frame is <b>dropped</b> and counted: a dropped frame becomes a sequence
 * gap, and gap recovery already rebuilds from a snapshot. Blocking to avoid the drop would trade
 * a recoverable gap for a dead connection.
 *
 * <h2>Ping/pong</h2>
 * No Pong is sent explicitly — the JDK's javadoc states the implementation "will automatically
 * send a reciprocal pong when a ping is received". Ping arrival is used only as a liveness
 * signal. Note that a live two-minute run received <b>zero</b> pings, so CLAUDE.md's ~10s ping
 * interval does not hold; the staleness check therefore keys off the last ping <i>or</i> frame,
 * or a quiet market would trigger a false reconnect.
 */
public final class KalshiWsConnection implements AutoCloseable {

    /** Verified live; see the class javadoc. Not the REST host. */
    public static final String DEFAULT_WS_URL = "wss://api.elections.kalshi.com/trade-api/ws/v2";

    /** The path that gets signed. Note {@code /ws/} — not the REST {@code /trade-api/v2}. */
    public static final String WS_SIGNING_PATH = "/trade-api/ws/v2";

    /**
     * Hand-off queue depth. Deep enough to absorb the snapshot burst on subscribe, shallow
     * enough that a stalled pump thread shows up as drops rather than an OutOfMemoryError.
     */
    private static final int QUEUE_CAPACITY = 65_536;

    /**
     * Silence longer than this means dead. Keyed off the last frame <i>or</i> ping.
     *
     * <p>Measured, not assumed: a live 3-minute run saw 16 pings across 4 sockets, i.e. roughly
     * one per 45s per socket. CLAUDE.md's "~every 10s" is wrong by more than 4x. An earlier 35s
     * timeout was therefore shorter than the actual ping interval — harmless while deltas flow,
     * but an overnight quiet market would have reconnected every 35 seconds forever. 120s allows
     * two and a half missed intervals.
     */
    private static final Duration STALE_TIMEOUT = Duration.ofSeconds(120);

    private static final Duration WATCHDOG_INTERVAL = Duration.ofSeconds(5);
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(20);

    private final String wsUrl;
    private final KalshiSigner signer;
    private final Clock clock;
    private final WsListener listener;
    private final WsMetrics metrics;
    private final WsFrameCodec codec = new WsFrameCodec();
    private final Backoff backoff;
    private final SubscriptionPlan.Shard shard;

    private final SequenceTracker sequences = new SequenceTracker();
    private final SnapshotRecovery recovery;

    private final BlockingQueue<RawFrame> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean reconnecting = new AtomicBoolean();
    private final AtomicLong lastPingNanos = new AtomicLong();
    private final AtomicLong lastFrameNanos = new AtomicLong();
    private final AtomicLong commandIds = new AtomicLong(1_000);

    /** The sid Kalshi assigned this connection. One per connection, by construction. */
    private volatile long assignedSid = -1;

    private volatile WebSocket socket;
    private volatile String state = "NEW";
    private Thread pumpThread;
    private Thread watchdogThread;
    private HttpClient http;

    public KalshiWsConnection(
            String wsUrl,
            KalshiSigner signer,
            Clock clock,
            SubscriptionPlan.Shard shard,
            WsListener listener,
            WsMetrics metrics,
            RandomGenerator random) {
        this.wsUrl = wsUrl;
        this.signer = signer;
        this.clock = clock;
        this.shard = shard;
        this.listener = listener;
        this.metrics = metrics;
        this.recovery = new SnapshotRecovery(sequences, metrics);
        this.backoff = Backoff.forWebSocket(random);
    }

    public SubscriptionPlan.Shard shard() {
        return shard;
    }

    public WsMetrics metrics() {
        return metrics;
    }

    public String state() {
        return state;
    }

    public long assignedSid() {
        return assignedSid;
    }

    /** Recovery state for this connection's single sid. */
    public SnapshotRecovery.State recoveryState() {
        return recovery.state(assignedSid);
    }

    /** Starts the pump and watchdog threads and connects. Returns once connected. */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("already started");
        }
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        String name = shard.family().value();
        pumpThread = new Thread(this::pumpLoop, "ws-pump-" + name);
        pumpThread.start();

        watchdogThread = new Thread(this::watchdogLoop, "ws-watchdog-" + name);
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

        AuthHeaders headers = signer.sign("GET", WS_SIGNING_PATH);

        socket = http.newWebSocketBuilder()
                .connectTimeout(HANDSHAKE_TIMEOUT)
                .header(AuthHeaders.HEADER_ACCESS_KEY, headers.accessKey())
                .header(AuthHeaders.HEADER_TIMESTAMP, headers.timestamp())
                .header(AuthHeaders.HEADER_SIGNATURE, headers.signature())
                .buildAsync(URI.create(wsUrl), new Handler())
                .get(HANDSHAKE_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS);

        long now = clock.nanoTime();
        lastPingNanos.set(now);
        lastFrameNanos.set(now);

        // Books do not survive a reconnect: the sid is reassigned and the sequence baseline is
        // gone. Force recovery before subscribing.
        recovery.invalidateAll();
        assignedSid = -1;

        transition("CONNECTED", "handshake accepted");
        send(WsCommands.subscribe(commandIds.incrementAndGet(), shard.markets()));
        transition("SUBSCRIBED", shard.family() + " (" + shard.size() + " markets)");
    }

    private void send(String json) {
        WebSocket ws = socket;
        if (ws == null) {
            throw new IllegalStateException("not connected");
        }
        ws.sendText(json, true);
    }

    // ------------------------------------------------------------------ WS callbacks

    private final class Handler implements WebSocket.Listener {

        /**
         * Accumulates message fragments. {@code onText} is called once per fragment with
         * {@code last=false} until the final one, and a JSON frame split across fragments would
         * fail to parse — which only shows up on large snapshots.
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

                // Timestamp before anything else touches the frame, so the recorded receive
                // time excludes our own decode cost.
                RawFrame frame = new RawFrame(
                        shard.family(), json, clock.currentTimeMillis(), clock.nanoTime());
                lastFrameNanos.set(frame.receivedAtNanos());
                metrics.frameReceived(frame.sizeBytes());

                if (!queue.offer(frame)) {
                    metrics.frameDropped();
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            // No explicit Pong: the JDK sends a reciprocal one automatically.
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
            if (frame != null) {
                handle(frame);
            }
        }
    }

    private void handle(RawFrame frame) {
        listener.onRawFrame(frame);

        WsFrame decoded;
        try {
            decoded = codec.decode(frame.json());
        } catch (RuntimeException e) {
            // One malformed frame must not kill the connection.
            metrics.decodeFailure();
            listener.onDecodeFailure(frame, e);
            return;
        }

        listener.onFrame(decoded, frame);

        if (decoded instanceof WsFrame.Subscribed sub) {
            if (sub.sid() >= 0) {
                assignedSid = sub.sid();
                transition(state, "sid " + sub.sid() + " -> " + shard.family());
            }
            return;
        }

        if (decoded instanceof WsFrame.Sequenced sequenced) {
            // An Ok carrying no seq is a bare acknowledgement, not part of the stream.
            if (sequenced instanceof WsFrame.Ok ok && !ok.hasSeq()) {
                return;
            }
            if (assignedSid < 0 && sequenced.sid() >= 0) {
                assignedSid = sequenced.sid();
            }

            SnapshotRecovery.Outcome outcome = recovery.onFrame(sequenced);
            listener.onRecoveryOutcome(outcome);

            // Recovery decides; the connection only performs the I/O it asks for. Neither
            // branch reconnects — a gap is repaired in place.
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
            transition(state, "get_snapshot for sid " + sid + " failed: " + e);
        }
    }

    // ------------------------------------------------------------------ watchdog

    private void watchdogLoop() {
        while (running.get()) {
            if (!sleepMillis(WATCHDOG_INTERVAL.toMillis()) || !running.get()) {
                return;
            }
            if (socket == null) {
                continue;
            }
            long quiet = clock.nanoTime()
                    - Math.max(lastPingNanos.get(), lastFrameNanos.get());
            if (quiet > STALE_TIMEOUT.toNanos()) {
                // No close frame, no error, just silence.
                transition("STALE", "no ping or frame for "
                        + TimeUnit.NANOSECONDS.toSeconds(quiet) + "s");
                scheduleReconnect("stale");
            }
        }
    }

    private void scheduleReconnect(String reason) {
        if (!running.get()) {
            return;
        }
        // onClose, onError and the watchdog can all fire for one failure; reconnect once.
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
        }, "ws-reconnect-" + shard.family().value());
        t.setDaemon(true);
        t.start();
    }

    private void abortSocket() {
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            // abort(), not sendClose(): the socket is already broken, and a graceful close
            // handshake would block on a peer that is not answering.
            ws.abort();
        }
    }

    /** Forces a reconnect. Used to exercise the reconnect path deliberately. */
    public void forceReconnect(String reason) {
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
}
