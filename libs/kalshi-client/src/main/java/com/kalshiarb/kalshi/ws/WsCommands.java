package com.kalshiarb.kalshi.ws;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.kalshiarb.model.MarketTicker;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Builds the outbound command frames.
 *
 * <p>Generated with Jackson rather than string concatenation. Tickers are alphanumeric with
 * dashes and dots today so hand-rolled JSON would work, but a ticker containing a quote would
 * produce a malformed frame that is very hard to see in a log.
 */
public final class WsCommands {

    /** The only channel this project needs; book state is what the constraints operate on. */
    public static final String CHANNEL_ORDERBOOK_DELTA = "orderbook_delta";

    private static final JsonFactory FACTORY = new JsonFactory();

    private WsCommands() {
    }

    /**
     * A subscribe command for one shard.
     *
     * <pre>{@code
     * {"id":1,"cmd":"subscribe","params":{
     *     "channels":["orderbook_delta"],
     *     "market_tickers":["KXHIGHNY-26SEP01-T83", ...]}}
     * }</pre>
     *
     * <p>Kalshi answers with one {@code orderbook_snapshot} per market, then a
     * {@code orderbook_delta} on every change.
     */
    public static String subscribe(long commandId, List<MarketTicker> markets) {
        return write(gen -> {
            gen.writeStartObject();
            gen.writeNumberField("id", commandId);
            gen.writeStringField("cmd", "subscribe");
            gen.writeObjectFieldStart("params");
            gen.writeArrayFieldStart("channels");
            gen.writeString(CHANNEL_ORDERBOOK_DELTA);
            gen.writeEndArray();
            gen.writeArrayFieldStart("market_tickers");
            for (MarketTicker m : markets) {
                gen.writeString(m.value());
            }
            gen.writeEndArray();
            gen.writeEndObject();
            gen.writeEndObject();
        });
    }

    /**
     * Requests a fresh snapshot for one subscription, the in-place gap recovery path.
     *
     * <pre>{@code
     * {"id":7,"cmd":"update_subscription","params":{
     *     "sids":[3],"market_tickers":["KXHIGHNY-26SEP01-T83",...],
     *     "action":"get_snapshot"}}
     * }</pre>
     *
     * <p><b>{@code market_tickers} is required.</b> The form documented in CLAUDE.md, with only
     * {@code sids} and {@code action}, is rejected:
     * {@code {"type":"error","sid":1,"seq":1261,"msg":{"code":14,"msg":"Market Ticker required"}}}.
     * That was found by replaying an archive and noticing 12 phantom sequence gaps, which turned
     * out to be the exchange's rejections of our own snapshot requests. Until this was fixed, gap
     * recovery could never have worked — it had simply never fired, because no gap had occurred.
     *
     * <p>Note this recovers a gap <b>without dropping the connection</b>. Reconnecting would
     * invalidate every other shard's books as collateral damage.
     */
    public static String getSnapshot(long commandId, long sid, List<MarketTicker> markets) {
        return write(gen -> {
            gen.writeStartObject();
            gen.writeNumberField("id", commandId);
            gen.writeStringField("cmd", "update_subscription");
            gen.writeObjectFieldStart("params");
            gen.writeArrayFieldStart("sids");
            gen.writeNumber(sid);
            gen.writeEndArray();
            gen.writeArrayFieldStart("market_tickers");
            for (MarketTicker m : markets) {
                gen.writeString(m.value());
            }
            gen.writeEndArray();
            gen.writeStringField("action", "get_snapshot");
            gen.writeEndObject();
            gen.writeEndObject();
        });
    }

    /** Unsubscribes one or more sids. */
    public static String unsubscribe(long commandId, List<Long> sids) {
        return write(gen -> {
            gen.writeStartObject();
            gen.writeNumberField("id", commandId);
            gen.writeStringField("cmd", "unsubscribe");
            gen.writeObjectFieldStart("params");
            gen.writeArrayFieldStart("sids");
            for (long sid : sids) {
                gen.writeNumber(sid);
            }
            gen.writeEndArray();
            gen.writeEndObject();
            gen.writeEndObject();
        });
    }

    private interface Body {
        void write(JsonGenerator gen) throws IOException;
    }

    private static String write(Body body) {
        StringWriter out = new StringWriter(256);
        try (JsonGenerator gen = FACTORY.createGenerator(out)) {
            body.write(gen);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot build WS command", e);
        }
        return out.toString();
    }
}
