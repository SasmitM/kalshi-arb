package com.kalshiarb.kalshi.ws;

import com.kalshiarb.model.EventTicker;
import com.kalshiarb.model.MarketTicker;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Splits a watchlist into one subscription per event family.
 *
 * <p>The reasoning, from CLAUDE.md section 5: {@code seq} is scoped to the subscription, so one
 * dropped frame invalidates every book on that {@code sid}. Putting the whole watchlist on a
 * single subscription means a single lost frame resnapshots everything. Sharding by event family
 * bounds that to one family's ~6 markets.
 *
 * <p><b>Why the event and not the series.</b> The event is the finest grain that still keeps a
 * constraint group whole. An exhaustive bracket set lives entirely within one event
 * ({@code mutually_exclusive} is an event-level flag), so no constraint ever spans two shards,
 * and a gap can never leave a detector comparing a fresh book against a stale one. Sharding by
 * series would also be correct but coarser — one gap would take out every day's event in that
 * series at once.
 *
 * <p>The valuable side effect: each shard's books are then owned by exactly one thread, and no
 * constraint group crosses threads. Single-writer-per-shard <i>is</i> the concurrency model, and
 * it is why there are no locks anywhere in the book layer.
 */
public final class SubscriptionPlan {

    /**
     * One subscription: all markets of a single event family, under one command id.
     *
     * @param family    the event whose markets these are
     * @param markets   the markets to subscribe, in stable order
     * @param commandId the {@code id} to send, which Kalshi echoes on the {@code subscribed} ack
     */
    public record Shard(EventTicker family, List<MarketTicker> markets, long commandId) {

        public Shard {
            Objects.requireNonNull(family, "family");
            markets = List.copyOf(markets);
            if (markets.isEmpty()) {
                throw new IllegalArgumentException("shard for " + family + " has no markets");
            }
        }

        public int size() {
            return markets.size();
        }
    }

    private SubscriptionPlan() {
    }

    /**
     * Groups {@code watchlist} by event family, assigning command ids from
     * {@code firstCommandId} upward.
     *
     * <p>Insertion order is preserved so the plan is reproducible: the same watchlist yields the
     * same shards with the same ids, which matters when correlating a recording against a later
     * replay.
     *
     * @throws IllegalArgumentException if the watchlist is empty or contains a ticker with no
     *                                 derivable event
     */
    public static List<Shard> shardByEventFamily(
            Collection<MarketTicker> watchlist, long firstCommandId) {
        Objects.requireNonNull(watchlist, "watchlist");
        if (watchlist.isEmpty()) {
            throw new IllegalArgumentException("watchlist is empty");
        }

        Map<EventTicker, List<MarketTicker>> byFamily = new LinkedHashMap<>();
        for (MarketTicker market : watchlist) {
            // Throws for a malformed ticker rather than inventing a catch-all shard, which
            // would silently rebuild the very blast radius this class exists to avoid.
            EventTicker family = market.eventTicker();
            byFamily.computeIfAbsent(family, k -> new ArrayList<>()).add(market);
        }

        List<Shard> shards = new ArrayList<>(byFamily.size());
        long commandId = firstCommandId;
        for (Map.Entry<EventTicker, List<MarketTicker>> e : byFamily.entrySet()) {
            shards.add(new Shard(e.getKey(), e.getValue(), commandId++));
        }
        return List.copyOf(shards);
    }

    public static List<Shard> shardByEventFamily(Collection<MarketTicker> watchlist) {
        return shardByEventFamily(watchlist, 1L);
    }
}
