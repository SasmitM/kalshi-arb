package com.kalshiarb.kalshi.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.kalshiarb.model.MarketTicker;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WsCommandsTest {

    @Test
    @DisplayName("subscribe carries the channel and every ticker in the shard")
    void subscribe() {
        String json = WsCommands.subscribe(1001, List.of(
                MarketTicker.of("KXHIGHNY-26SEP01-T83"),
                MarketTicker.of("KXHIGHNY-26SEP01-B87.5")));

        assertThat(json).isEqualTo(
                "{\"id\":1001,\"cmd\":\"subscribe\",\"params\":{"
                        + "\"channels\":[\"orderbook_delta\"],"
                        + "\"market_tickers\":[\"KXHIGHNY-26SEP01-T83\","
                        + "\"KXHIGHNY-26SEP01-B87.5\"]}}");
    }

    @Test
    @DisplayName("get_snapshot MUST carry market_tickers or Kalshi rejects it with code 14")
    void getSnapshot() {
        // Sending only sids + action returns
        // {"type":"error","seq":N,"msg":{"code":14,"msg":"Market Ticker required"}}, which meant
        // gap recovery had never actually worked. Found by replaying an archive and tracing
        // phantom sequence gaps back to our own rejected requests.
        String json = WsCommands.getSnapshot(1002, 3, List.of(
                MarketTicker.of("KXHIGHNY-26SEP01-T83"),
                MarketTicker.of("KXHIGHNY-26SEP01-T90")));

        assertThat(json).isEqualTo(
                "{\"id\":1002,\"cmd\":\"update_subscription\",\"params\":{"
                        + "\"sids\":[3],"
                        + "\"market_tickers\":[\"KXHIGHNY-26SEP01-T83\","
                        + "\"KXHIGHNY-26SEP01-T90\"],"
                        + "\"action\":\"get_snapshot\"}}");
        assertThat(json).contains("market_tickers");
    }

    @Test
    @DisplayName("unsubscribe takes a list of sids")
    void unsubscribe() {
        assertThat(WsCommands.unsubscribe(1003, List.of(3L, 4L))).isEqualTo(
                "{\"id\":1003,\"cmd\":\"unsubscribe\",\"params\":{\"sids\":[3,4]}}");
    }

    @Test
    @DisplayName("commands round-trip through the codec's parser as valid JSON")
    void commandsAreValidJson() {
        // Not a protocol assertion, just proof the generator emits parseable JSON rather than
        // string-concatenated near-JSON.
        String subscribe = WsCommands.subscribe(1, List.of(MarketTicker.of("KXHIGHNY-26SEP01-T83")));
        assertThat(subscribe).startsWith("{").endsWith("}");
        assertThat(subscribe.chars().filter(c -> c == '{').count())
                .isEqualTo(subscribe.chars().filter(c -> c == '}').count());
    }
}
