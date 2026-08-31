package com.kalshiarb.kalshi.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kalshiarb.model.EventTicker;
import com.kalshiarb.model.MarketTicker;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SubscriptionPlanTest {

    private static List<MarketTicker> tickers(String... raw) {
        return java.util.Arrays.stream(raw).map(MarketTicker::of).toList();
    }

    @Test
    @DisplayName("one shard per event family, so a gap costs six markets not the watchlist")
    void shardsByEventFamily() {
        List<SubscriptionPlan.Shard> shards = SubscriptionPlan.shardByEventFamily(tickers(
                "KXHIGHNY-26SEP01-T83", "KXHIGHNY-26SEP01-B83.5", "KXHIGHNY-26SEP01-B85.5",
                "KXHIGHNY-26SEP01-B87.5", "KXHIGHNY-26SEP01-B89.5", "KXHIGHNY-26SEP01-T90",
                "KXHIGHLAX-26SEP01-T83", "KXHIGHLAX-26SEP01-B83.5"));

        assertThat(shards).hasSize(2);
        assertThat(shards.get(0).family()).isEqualTo(EventTicker.of("KXHIGHNY-26SEP01"));
        assertThat(shards.get(0).size()).isEqualTo(6);
        assertThat(shards.get(1).family()).isEqualTo(EventTicker.of("KXHIGHLAX-26SEP01"));
        assertThat(shards.get(1).size()).isEqualTo(2);
    }

    @Test
    @DisplayName("the same city on different days shards separately")
    void differentDaysAreDifferentShards() {
        List<SubscriptionPlan.Shard> shards = SubscriptionPlan.shardByEventFamily(tickers(
                "KXHIGHNY-26SEP01-T83", "KXHIGHNY-26AUG31-T83"));

        assertThat(shards).hasSize(2);
        assertThat(shards).extracting(s -> s.family().value())
                .containsExactly("KXHIGHNY-26SEP01", "KXHIGHNY-26AUG31");
    }

    @Test
    @DisplayName("no constraint group spans two shards — the reason for event-level sharding")
    void constraintGroupNeverSpansShards() {
        List<MarketTicker> watchlist = tickers(
                "KXHIGHNY-26SEP01-T83", "KXHIGHLAX-26SEP01-T83",
                "KXHIGHNY-26SEP01-T90", "KXHIGHLAX-26SEP01-T90");

        List<SubscriptionPlan.Shard> shards = SubscriptionPlan.shardByEventFamily(watchlist);

        // Every market in a shard belongs to that shard's event, so a bracket set is never
        // split across two sequence streams.
        assertThat(shards).allSatisfy(shard ->
                assertThat(shard.markets())
                        .allSatisfy(m -> assertThat(m.eventTicker()).isEqualTo(shard.family())));
    }

    @Test
    @DisplayName("command ids are assigned in order and are distinct")
    void assignsDistinctCommandIds() {
        List<SubscriptionPlan.Shard> shards = SubscriptionPlan.shardByEventFamily(tickers(
                "KXHIGHNY-26SEP01-T83", "KXHIGHLAX-26SEP01-T83", "KXHIGHCHI-26SEP01-T83"), 100L);

        assertThat(shards).extracting(SubscriptionPlan.Shard::commandId)
                .containsExactly(100L, 101L, 102L);
    }

    @Test
    @DisplayName("the plan is reproducible: same watchlist, same shards and ids")
    void planIsReproducible() {
        List<MarketTicker> watchlist = tickers(
                "KXHIGHNY-26SEP01-T83", "KXHIGHLAX-26SEP01-T83", "KXHIGHNY-26SEP01-T90");

        assertThat(SubscriptionPlan.shardByEventFamily(watchlist))
                .isEqualTo(SubscriptionPlan.shardByEventFamily(watchlist));
    }

    @Test
    @DisplayName("markets keep their input order within a shard")
    void preservesOrderWithinShard() {
        List<SubscriptionPlan.Shard> shards = SubscriptionPlan.shardByEventFamily(tickers(
                "KXHIGHNY-26SEP01-T90", "KXHIGHNY-26SEP01-T83", "KXHIGHNY-26SEP01-B85.5"));

        assertThat(shards).hasSize(1);
        assertThat(shards.get(0).markets()).extracting(MarketTicker::value)
                .containsExactly("KXHIGHNY-26SEP01-T90", "KXHIGHNY-26SEP01-T83",
                        "KXHIGHNY-26SEP01-B85.5");
    }

    @Test
    @DisplayName("an empty watchlist and a malformed ticker are both rejected")
    void rejectsBadInput() {
        assertThatThrownBy(() -> SubscriptionPlan.shardByEventFamily(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");

        // No strike segment, so no event can be derived. Better to fail than to invent a
        // catch-all shard that recreates the blast radius we are avoiding.
        assertThatThrownBy(() -> SubscriptionPlan.shardByEventFamily(tickers("KXHIGHNY")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("shard market lists are immutable")
    void shardsAreImmutable() {
        SubscriptionPlan.Shard shard = SubscriptionPlan
                .shardByEventFamily(tickers("KXHIGHNY-26SEP01-T83")).get(0);

        assertThatThrownBy(() -> shard.markets().add(MarketTicker.of("KXHIGHNY-26SEP01-T90")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
