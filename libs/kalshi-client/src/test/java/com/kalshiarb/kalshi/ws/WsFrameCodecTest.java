package com.kalshiarb.kalshi.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kalshiarb.model.FixedPoint;
import com.kalshiarb.model.Price;
import com.kalshiarb.model.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WsFrameCodecTest {

    private final WsFrameCodec codec = new WsFrameCodec();

    @Test
    @DisplayName("decodes a real live snapshot: yes_dollars_fp, market_id, both suffixes")
    void liveSnapshotFieldNames() {
        // Captured verbatim from the first live socket run. The field names are
        // yes_dollars_fp / no_dollars_fp -- NOT the yes_dollars that the REST orderbook
        // endpoint uses inside orderbook_fp. Assuming the REST spelling made every snapshot
        // fail to decode while deltas kept working, which is a nasty failure mode: books
        // silently never initialise.
        String json = """
                {"type":"orderbook_snapshot","sid":2,"seq":4,"msg":{
                  "market_ticker":"KXHIGHNY-26SEP01-B89.5",
                  "market_id":"f447cb57-f3de-44d8-a984-5ec80259928e",
                  "yes_dollars_fp":[["0.0100","869.79"]],
                  "no_dollars_fp":[["0.0100","569.00"],["0.0200","20.00"],
                                   ["0.0300","183.00"]]}}""";

        WsFrame.Snapshot snap = (WsFrame.Snapshot) codec.decode(json);

        assertThat(snap.sid()).isEqualTo(2);
        assertThat(snap.seq()).isEqualTo(4);
        assertThat(snap.ticker().value()).isEqualTo("KXHIGHNY-26SEP01-B89.5");
        assertThat(snap.yesBids()).hasSize(1);
        assertThat(snap.yesBids().get(0).quantity().toContractString()).isEqualTo("869.79");
        assertThat(snap.noBids()).hasSize(3);
        assertThat(snap.noBids().get(2).price()).isEqualTo(Price.ofCents(3));
    }

    @Test
    @DisplayName("a snapshot with only no_dollars_fp decodes with an empty yes side")
    void liveOneSidedSnapshot() {
        // KXHIGHNY-26SEP01-T90 really does have no YES bids.
        String json = """
                {"type":"orderbook_snapshot","sid":2,"seq":6,"msg":{
                  "market_ticker":"KXHIGHNY-26SEP01-T90",
                  "market_id":"f399c488-064b-466c-9c28-87075c27e5d3",
                  "no_dollars_fp":[["0.0100","519.00"],["0.0200","20.00"]]}}""";

        WsFrame.Snapshot snap = (WsFrame.Snapshot) codec.decode(json);

        assertThat(snap.yesBids()).isEmpty();
        assertThat(snap.noBids()).hasSize(2);
    }

    @Test
    @DisplayName("_dollars_fp wins over the REST-style _dollars spelling")
    void dollarsFpPreferredOverDollars() {
        String json = """
                {"type":"orderbook_snapshot","sid":1,"seq":1,"msg":{
                  "market_ticker":"KXHIGHNY-26SEP01-T83",
                  "yes_dollars":[["0.4000","1.00"]],
                  "yes_dollars_fp":[["0.4500","2.00"]],
                  "no_dollars_fp":[]}}""";

        WsFrame.Snapshot snap = (WsFrame.Snapshot) codec.decode(json);

        assertThat(snap.yesBids().get(0).price()).isEqualTo(Price.ofCents(45));
    }

    @Test
    @DisplayName("a real live delta: price_dollars/delta_fp plus the server ts_ms")
    void liveDeltaWithTimestamp() {
        // Captured verbatim. Confirms deltas take the fixed-point branch, not the lossy legacy
        // whole-cents one, and that Kalshi stamps each frame server-side.
        String json = """
                {"type":"orderbook_delta","sid":1,"seq":28,"msg":{
                  "market_ticker":"KXHIGHLAX-26SEP01-B76.5",
                  "market_id":"78a9ab48-fcf5-4b06-afdd-f6bfc4c7e6c8",
                  "price_dollars":"0.3900","delta_fp":"0.33","side":"yes",
                  "ts":"2026-08-31T20:51:05.261155Z","ts_ms":1788209465261}}""";

        WsFrame.Delta d = (WsFrame.Delta) codec.decode(json);

        assertThat(d.price()).isEqualTo(Price.ofCents(39));
        // A fractional delta of 0.33 contracts, which whole-contract legacy could not express.
        assertThat(d.deltaMicroContracts()).isEqualTo(330_000L);
        assertThat(d.hasExchangeTimestamp()).isTrue();
        assertThat(d.exchangeTimestampMillis()).isEqualTo(1_788_209_465_261L);
    }

    @Test
    @DisplayName("a frame without ts_ms reports no exchange timestamp rather than epoch zero")
    void absentTimestampIsNegativeOne() {
        WsFrame.Delta d = (WsFrame.Delta) codec.decode("""
                {"type":"orderbook_delta","sid":1,"seq":2,"msg":{
                  "market_ticker":"KXHIGHNY-26SEP01-T83","side":"yes",
                  "price_dollars":"0.4500","delta_fp":"1.00"}}""");

        // 0 would be 1970 and would silently poison any latency histogram.
        assertThat(d.exchangeTimestampMillis()).isEqualTo(-1L);
        assertThat(d.hasExchangeTimestamp()).isFalse();
    }

    @Test
    @DisplayName("an ok frame reports whether it carries a seq")
    void okFrame() {
        // Whether ok frames sit inside the sequence stream decides if the tracker sees false
        // gaps at every subscribe ack. Both shapes must be distinguishable.
        WsFrame.Ok withSeq = (WsFrame.Ok) codec.decode("""
                {"type":"ok","id":1002,"sid":2,"seq":7}""");
        assertThat(withSeq.hasSeq()).isTrue();
        assertThat(withSeq.seq()).isEqualTo(7);
        assertThat(withSeq.sid()).isEqualTo(2);

        WsFrame.Ok withoutSeq = (WsFrame.Ok) codec.decode("""
                {"type":"ok","id":1002,"sid":2}""");
        assertThat(withoutSeq.hasSeq()).isFalse();
        assertThat(withoutSeq.seq()).isEqualTo(-1);
    }

    @Test
    @DisplayName("decodes a fixed-point snapshot")
    void fixedPointSnapshot() {
        String json = """
                {"type":"orderbook_snapshot","sid":3,"seq":1,"msg":{
                  "market_ticker":"KXHIGHNY-26SEP01-T83",
                  "yes_dollars":[["0.0100","1408.00"],["0.4900","150.44"]],
                  "no_dollars":[["0.0600","102.12"]]}}""";

        WsFrame.Snapshot snap = (WsFrame.Snapshot) codec.decode(json);

        assertThat(snap.sid()).isEqualTo(3);
        assertThat(snap.seq()).isEqualTo(1);
        assertThat(snap.ticker().value()).isEqualTo("KXHIGHNY-26SEP01-T83");
        assertThat(snap.yesBids()).hasSize(2);
        assertThat(snap.yesBids().get(1).price()).isEqualTo(Price.ofCents(49));
        assertThat(snap.yesBids().get(1).quantity().toContractString()).isEqualTo("150.44");
        assertThat(snap.noBids().get(0).quantity().toContractString()).isEqualTo("102.12");
    }

    @Test
    @DisplayName("decodes a legacy integer snapshot: cents and whole contracts")
    void legacySnapshot() {
        String json = """
                {"type":"orderbook_snapshot","sid":3,"seq":1,"msg":{
                  "market_ticker":"KXHIGHNY-26SEP01-T83",
                  "yes":[[45,100],[50,25]],
                  "no":[[48,10]]}}""";

        WsFrame.Snapshot snap = (WsFrame.Snapshot) codec.decode(json);

        assertThat(snap.yesBids().get(0).price()).isEqualTo(Price.ofCents(45));
        assertThat(snap.yesBids().get(0).quantity().microContracts()).isEqualTo(100_000_000L);
        assertThat(snap.noBids().get(0).price()).isEqualTo(Price.ofCents(48));
    }

    @Test
    @DisplayName("fixed-point wins when both representations are present")
    void fixedPointWinsOverLegacy() {
        String json = """
                {"type":"orderbook_snapshot","sid":1,"seq":1,"msg":{
                  "market_ticker":"KXHIGHNY-26SEP01-T83",
                  "yes":[[45,100]],
                  "yes_dollars":[["0.4550","100.50"]],
                  "no":[],"no_dollars":[]}}""";

        WsFrame.Snapshot snap = (WsFrame.Snapshot) codec.decode(json);

        // Sub-penny price and fractional count survive; the legacy field would have lost both.
        assertThat(snap.yesBids().get(0).price().micros()).isEqualTo(455_000L);
        assertThat(snap.yesBids().get(0).quantity().toContractString()).isEqualTo("100.50");
    }

    @Test
    @DisplayName("decodes a fixed-point delta, including a negative one")
    void fixedPointDelta() {
        String positive = """
                {"type":"orderbook_delta","sid":3,"seq":2,"msg":{
                  "market_ticker":"KXHIGHNY-26SEP01-T83","side":"yes",
                  "price_dollars":"0.4500","delta_fp":"100.00"}}""";

        WsFrame.Delta d = (WsFrame.Delta) codec.decode(positive);
        assertThat(d.side()).isEqualTo(Side.YES);
        assertThat(d.price()).isEqualTo(Price.ofCents(45));
        assertThat(d.deltaMicroContracts()).isEqualTo(100_000_000L);
        assertThat(d.isRemoval()).isFalse();

        String negative = """
                {"type":"orderbook_delta","sid":3,"seq":3,"msg":{
                  "market_ticker":"KXHIGHNY-26SEP01-T83","side":"no",
                  "price_dollars":"0.4800","delta_fp":"-40.50"}}""";

        WsFrame.Delta neg = (WsFrame.Delta) codec.decode(negative);
        assertThat(neg.side()).isEqualTo(Side.NO);
        assertThat(neg.deltaMicroContracts()).isEqualTo(-40_500_000L);
        assertThat(neg.isRemoval()).isTrue();
    }

    @Test
    @DisplayName("decodes a legacy delta with a negative contract count")
    void legacyDelta() {
        String json = """
                {"type":"orderbook_delta","sid":3,"seq":2,"msg":{
                  "market_ticker":"KXHIGHNY-26SEP01-T83","side":"yes",
                  "price":45,"delta":-30}}""";

        WsFrame.Delta d = (WsFrame.Delta) codec.decode(json);

        assertThat(d.price()).isEqualTo(Price.ofCents(45));
        assertThat(d.deltaMicroContracts()).isEqualTo(-30 * FixedPoint.ONE);
    }

    @Test
    @DisplayName("field order does not matter, including msg before type")
    void fieldOrderIndependent() {
        String msgFirst = """
                {"msg":{"market_ticker":"KXHIGHNY-26SEP01-T83","side":"yes",
                        "price_dollars":"0.4500","delta_fp":"7.00"},
                 "seq":9,"sid":2,"type":"orderbook_delta"}""";

        WsFrame.Delta d = (WsFrame.Delta) codec.decode(msgFirst);

        assertThat(d.sid()).isEqualTo(2);
        assertThat(d.seq()).isEqualTo(9);
        assertThat(d.deltaMicroContracts()).isEqualTo(7_000_000L);
    }

    @Test
    @DisplayName("unknown envelope and msg fields are skipped, not fatal")
    void ignoresUnknownFields() {
        String json = """
                {"type":"orderbook_delta","sid":3,"seq":2,
                 "future_field":{"nested":[1,2,3]},"another":"x",
                 "msg":{"market_ticker":"KXHIGHNY-26SEP01-T83","side":"yes",
                        "price_dollars":"0.4500","delta_fp":"1.00",
                        "brand_new":{"deep":{"deeper":[true,null]}}}}""";

        WsFrame.Delta d = (WsFrame.Delta) codec.decode(json);
        assertThat(d.deltaMicroContracts()).isEqualTo(1_000_000L);
    }

    @Test
    @DisplayName("decodes a subscribed ack with the sid in msg")
    void subscribedAck() {
        String json = """
                {"type":"subscribed","id":1001,
                 "msg":{"channel":"orderbook_delta","sid":4}}""";

        WsFrame.Subscribed sub = (WsFrame.Subscribed) codec.decode(json);

        assertThat(sub.commandId()).isEqualTo(1001);
        assertThat(sub.sid()).isEqualTo(4);
        assertThat(sub.channel()).isEqualTo("orderbook_delta");
    }

    @Test
    @DisplayName("decodes an error frame")
    void errorFrame() {
        String json = """
                {"type":"error","id":1001,
                 "msg":{"code":"6","msg":"already subscribed"}}""";

        WsFrame.ErrorFrame err = (WsFrame.ErrorFrame) codec.decode(json);

        assertThat(err.commandId()).isEqualTo(1001);
        assertThat(err.code()).isEqualTo("6");
        assertThat(err.message()).isEqualTo("already subscribed");
    }

    @Test
    @DisplayName("an unmodelled type becomes Unhandled instead of throwing")
    void unknownTypeIsTolerated() {
        WsFrame frame = codec.decode("""
                {"type":"some_new_channel","sid":1,"msg":{"whatever":1}}""");

        assertThat(frame).isInstanceOf(WsFrame.Unhandled.class);
        assertThat(frame.type()).isEqualTo("some_new_channel");
    }

    @Test
    @DisplayName("a snapshot with only one side populated decodes with the other empty")
    void oneSidedSnapshot() {
        String json = """
                {"type":"orderbook_snapshot","sid":1,"seq":1,"msg":{
                  "market_ticker":"KXHIGHNY-26SEP01-T90",
                  "no_dollars":[["0.9900","557.00"]]}}""";

        WsFrame.Snapshot snap = (WsFrame.Snapshot) codec.decode(json);

        // Matches the live T90 book: no YES bids at all.
        assertThat(snap.yesBids()).isEmpty();
        assertThat(snap.noBids()).hasSize(1);
    }

    @Test
    @DisplayName("a book frame with no recognisable representation names the problem")
    void missingRepresentationThrows() {
        assertThatThrownBy(() -> codec.decode("""
                {"type":"orderbook_delta","sid":1,"seq":1,"msg":{
                  "market_ticker":"KXHIGHNY-26SEP01-T83","side":"yes"}}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no recognised price field");

        assertThatThrownBy(() -> codec.decode("""
                {"type":"orderbook_snapshot","sid":1,"seq":1,"msg":{
                  "market_ticker":"KXHIGHNY-26SEP01-T83"}}"""))
                .isInstanceOf(IllegalArgumentException.class)
                // The message must list what was tried; that is what made the real
                // yes_dollars_fp mismatch diagnosable from one log line.
                .hasMessageContaining("no recognised level fields")
                .hasMessageContaining("yes_dollars_fp");
    }

    @Test
    @DisplayName("malformed frames are rejected with the frame in the message")
    void malformedRejected() {
        assertThatThrownBy(() -> codec.decode("[1,2,3]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a JSON object");

        assertThatThrownBy(() -> codec.decode("""
                {"sid":1,"seq":2}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no type field");

        assertThatThrownBy(() -> codec.decode("""
                {"type":"orderbook_delta","sid":1,"seq":1,"msg":{
                  "side":"yes","price_dollars":"0.45","delta_fp":"1"}}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no market_ticker");
    }

    @Test
    @DisplayName("an unknown side value is rejected rather than defaulted")
    void unknownSideRejected() {
        assertThatThrownBy(() -> codec.decode("""
                {"type":"orderbook_delta","sid":1,"seq":1,"msg":{
                  "market_ticker":"KXHIGHNY-26SEP01-T83","side":"maybe",
                  "price_dollars":"0.45","delta_fp":"1"}}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown side");
    }
}
