package com.kalshiarb.kalshi.ws;

import com.kalshiarb.common.Clock;
import com.kalshiarb.kalshi.auth.KalshiSigner;
import com.kalshiarb.model.EventTicker;
import com.kalshiarb.model.MarketTicker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * One {@link KalshiWsConnection} per event family, which is what actually bounds the blast
 * radius of a sequence gap.
 *
 * <p>Kalshi assigns one {@code sid} per channel per connection, so multiple {@code subscribe}
 * commands on a single socket all share one sequence stream — verified live, four families on
 * {@code sid=1} with {@code seq} continuous across all of them. Isolation therefore has to come
 * from separate sockets. {@link SubscriptionPlan} still decides the grouping; this class just
 * gives each group its own connection.
 *
 * <p>The cost is one socket, one pump thread and one watchdog thread per family. That is the
 * price of the guarantee, and it is worth stating plainly: a gap on the NYC socket cannot touch
 * the LAX books, and because a constraint group never spans families, a detector can never
 * compare a fresh book against a stale one. It also makes single-writer-per-shard structural
 * rather than aspirational — each family's books are touched by exactly one pump thread — which
 * is what keeps locks out of the book layer.
 *
 * <p>Connections are independent: each has its own backoff schedule, so one family reconnecting
 * does not disturb the others.
 */
public final class KalshiWsCluster implements AutoCloseable {

    private final Map<EventTicker, KalshiWsConnection> connections = new LinkedHashMap<>();
    private final WsMetrics metrics = new WsMetrics();
    private final List<SubscriptionPlan.Shard> shards;

    public KalshiWsCluster(
            String wsUrl,
            KalshiSigner signer,
            Clock clock,
            List<MarketTicker> watchlist,
            WsListener listener,
            RandomGenerator random) {
        this.shards = SubscriptionPlan.shardByEventFamily(watchlist);
        for (SubscriptionPlan.Shard shard : shards) {
            connections.put(shard.family(), new KalshiWsConnection(
                    wsUrl, signer, clock, shard, listener, metrics, random));
        }
    }

    public List<SubscriptionPlan.Shard> shards() {
        return shards;
    }

    /** Aggregated across every connection. */
    public WsMetrics metrics() {
        return metrics;
    }

    public List<KalshiWsConnection> connections() {
        return List.copyOf(connections.values());
    }

    public Optional<KalshiWsConnection> connection(EventTicker family) {
        return Optional.ofNullable(connections.get(family));
    }

    /**
     * Starts every connection.
     *
     * <p>Sequentially, so the handshakes do not arrive as a burst. If one family fails to
     * connect its own retry loop owns the problem; the others are unaffected.
     */
    public void start() {
        for (KalshiWsConnection c : connections.values()) {
            c.start();
        }
    }

    /** Forces a reconnect on one family. */
    public void forceReconnect(EventTicker family, String reason) {
        connection(family).ifPresent(c -> c.forceReconnect(reason));
    }

    /** Forces a reconnect on every family. */
    public void forceReconnectAll(String reason) {
        for (KalshiWsConnection c : connections.values()) {
            c.forceReconnect(reason);
        }
    }

    /** Per-family state, for reporting. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>(connections.size());
        for (KalshiWsConnection c : connections.values()) {
            lines.add(String.format("%-22s sid=%-4d %-15s %s (%d markets)",
                    c.shard().family().value(),
                    c.assignedSid(),
                    c.state(),
                    c.recoveryState(),
                    c.shard().size()));
        }
        return lines;
    }

    @Override
    public void close() {
        RuntimeException first = null;
        for (KalshiWsConnection c : connections.values()) {
            try {
                c.close();
            } catch (RuntimeException e) {
                // Keep closing the rest; a stuck socket must not leak the other threads.
                if (first == null) {
                    first = e;
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
