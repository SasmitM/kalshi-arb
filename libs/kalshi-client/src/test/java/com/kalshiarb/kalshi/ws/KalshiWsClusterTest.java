package com.kalshiarb.kalshi.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kalshiarb.common.Clock;
import com.kalshiarb.kalshi.auth.KalshiSigner;
import com.kalshiarb.model.EventTicker;
import com.kalshiarb.model.MarketTicker;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.List;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Structural tests only — no socket is opened. The point is that the fan-out is one connection
 * per event family, which is the property that actually bounds a gap's blast radius.
 */
class KalshiWsClusterTest {

    private static KalshiSigner signer;

    @BeforeAll
    static void throwawayKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        signer = new KalshiSigner(
                "test-key", (RSAPrivateKey) gen.generateKeyPair().getPrivate(), Clock.fixed(0));
    }

    private static KalshiWsCluster cluster(String... tickers) {
        return new KalshiWsCluster(
                "wss://example.invalid/trade-api/ws/v2",
                signer,
                Clock.system(),
                java.util.Arrays.stream(tickers).map(MarketTicker::of).toList(),
                new WsListener() {
                },
                RandomGenerator.getDefault());
    }

    @Test
    @DisplayName("one connection per event family, each owning only its own markets")
    void oneConnectionPerFamily() {
        try (KalshiWsCluster c = cluster(
                "KXHIGHNY-26SEP01-T83", "KXHIGHNY-26SEP01-T90",
                "KXHIGHLAX-26SEP01-T83",
                "KXHIGHNY-26AUG31-T85")) {

            assertThat(c.connections()).hasSize(3);
            assertThat(c.shards()).hasSize(3);

            // Kalshi gives one sid per connection, so isolation requires one connection per
            // family; a shard spanning families would defeat the whole arrangement.
            assertThat(c.connections()).allSatisfy(conn ->
                    assertThat(conn.shard().markets()).allSatisfy(m ->
                            assertThat(m.eventTicker()).isEqualTo(conn.shard().family())));

            assertThat(c.connection(EventTicker.of("KXHIGHNY-26SEP01")))
                    .isPresent()
                    .get()
                    .satisfies(conn -> assertThat(conn.shard().size()).isEqualTo(2));
            assertThat(c.connection(EventTicker.of("KXHIGHCHI-26SEP01"))).isEmpty();
        }
    }

    @Test
    @DisplayName("metrics are shared so cluster totals aggregate across sockets")
    void metricsAreShared() {
        try (KalshiWsCluster c = cluster(
                "KXHIGHNY-26SEP01-T83", "KXHIGHLAX-26SEP01-T83")) {

            assertThat(c.connections())
                    .allSatisfy(conn -> assertThat(conn.metrics()).isSameAs(c.metrics()));
        }
    }

    @Test
    @DisplayName("every connection starts un-assigned and awaiting a snapshot")
    void startsAwaitingSnapshot() {
        try (KalshiWsCluster c = cluster("KXHIGHNY-26SEP01-T83", "KXHIGHLAX-26SEP01-T83")) {
            assertThat(c.connections()).allSatisfy(conn -> {
                assertThat(conn.assignedSid()).isEqualTo(-1);
                assertThat(conn.state()).isEqualTo("NEW");
                assertThat(conn.recoveryState())
                        .isEqualTo(SnapshotRecovery.State.AWAITING_SNAPSHOT);
            });
        }
    }

    @Test
    @DisplayName("describe reports one line per family")
    void describeReportsEachFamily() {
        try (KalshiWsCluster c = cluster(
                "KXHIGHNY-26SEP01-T83", "KXHIGHLAX-26SEP01-T83", "KXHIGHCHI-26SEP01-T83")) {

            assertThat(c.describe()).hasSize(3);
            assertThat(String.join("\n", c.describe()))
                    .contains("KXHIGHNY-26SEP01")
                    .contains("KXHIGHLAX-26SEP01")
                    .contains("KXHIGHCHI-26SEP01");
        }
    }

    @Test
    @DisplayName("an empty watchlist is rejected before any socket is created")
    void rejectsEmptyWatchlist() {
        assertThatThrownBy(KalshiWsClusterTest::cluster)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("closing an unstarted cluster is safe and idempotent")
    void closeIsIdempotent() {
        KalshiWsCluster c = cluster("KXHIGHNY-26SEP01-T83");
        c.close();
        c.close();
        assertThat(c.connections()).hasSize(1);
    }

    private static KalshiWsCluster cluster() {
        return new KalshiWsCluster(
                "wss://example.invalid/trade-api/ws/v2", signer, Clock.system(),
                List.of(), new WsListener() {
                }, RandomGenerator.getDefault());
    }
}
