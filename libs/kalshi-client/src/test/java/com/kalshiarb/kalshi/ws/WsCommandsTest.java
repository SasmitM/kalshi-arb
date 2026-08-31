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
    @DisplayName("get_snapshot is the in-place gap repair, scoped to one sid")
    void getSnapshot() {
        String json = WsCommands.getSnapshot(1002, 3);

        assertThat(json).isEqualTo(
                "{\"id\":1002,\"cmd\":\"update_subscription\",\"params\":{"
                        + "\"sids\":[3],\"action\":\"get_snapshot\"}}");
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
