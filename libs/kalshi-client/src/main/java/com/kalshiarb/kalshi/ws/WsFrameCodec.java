package com.kalshiarb.kalshi.ws;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.kalshiarb.model.FixedPoint;
import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.Price;
import com.kalshiarb.model.PriceLevel;
import com.kalshiarb.model.Quantity;
import com.kalshiarb.model.Side;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes WebSocket frames with Jackson's streaming {@link JsonParser}.
 *
 * <p>This is the hot path — one frame per book change, continuously — so there is no
 * {@code readTree} and no databind here. The envelope is walked once, token by token.
 *
 * <p><b>Field order is not assumed.</b> A naive streaming decoder dispatches on {@code type} and
 * then parses {@code msg}, which breaks if {@code msg} arrives first. Instead the {@code msg}
 * object is drained into a small scratch holder and the frame is assembled afterwards, so any
 * key order works. That costs one short-lived object per frame and buys immunity to an ordering
 * assumption nothing in the protocol guarantees.
 *
 * <p><b>Field names differ between frame types, and the snapshot names are not the REST ones.</b>
 * Confirmed against a live socket on 2026-08-31:
 *
 * <ul>
 *   <li>{@code orderbook_snapshot} carries <b>{@code yes_dollars_fp}</b> /
 *       <b>{@code no_dollars_fp}</b> — note both suffixes — as
 *       {@code [["0.0100","1281.00"]]}. The REST orderbook endpoint uses {@code yes_dollars}
 *       inside {@code orderbook_fp}, so the two surfaces genuinely disagree. An earlier version
 *       of this codec assumed the REST spelling and failed to decode every snapshot.
 *   <li>{@code orderbook_snapshot} also carries a {@code market_id} UUID, which is skipped.
 * </ul>
 *
 * Every known spelling is therefore tried in order rather than assumed:
 * {@code yes_dollars_fp} then {@code yes_dollars} then legacy {@code yes}; for deltas
 * {@code price_dollars_fp} then {@code price_dollars} then legacy {@code price}. Fixed-point
 * always wins over legacy, since legacy prices are whole cents and cannot represent a sub-penny
 * level.
 *
 * <p>Legacy form, for reference: {@code yes}/{@code no} as {@code [[45,100]]}, {@code price},
 * {@code delta} — price in <b>whole cents</b>, count in <b>whole contracts</b>.
 *
 * <p>A book frame matching none of the spellings throws with the frame text and the list of
 * names tried, rather than silently producing an empty book. That is what surfaced the
 * {@code _dollars_fp} discrepancy in the first two hundred milliseconds of the first live run
 * instead of after a day of recording.
 *
 * <p>Stateless and thread-safe.
 */
public final class WsFrameCodec {

    private static final JsonFactory FACTORY = new JsonFactory();

    /** Mutable scratch for one {@code msg} object. Never escapes {@link #decode}. */
    private static final class Msg {
        String marketTicker;
        String channel;
        String side;

        // Fixed-point forms, in descending order of preference. Kalshi is inconsistent between
        // frame types, so each name is tried rather than assumed -- see the class javadoc.
        List<PriceLevel> yesDollarsFp;
        List<PriceLevel> noDollarsFp;
        List<PriceLevel> yesDollars;
        List<PriceLevel> noDollars;
        String priceDollarsFp;
        String priceDollars;
        String deltaFp;

        // Legacy integer forms.
        boolean hasLegacyPrice;
        long legacyPriceCents;
        boolean hasLegacyDelta;
        long legacyDeltaContracts;
        List<PriceLevel> yesLegacy;
        List<PriceLevel> noLegacy;

        // Error payload.
        String code;
        String message;

        boolean hasSid;
        long sid;

        // Kalshi's own send timestamp. Observed on every delta as ts_ms (epoch millis)
        // alongside a human-readable ts. This is what makes delivery latency measurable
        // instead of modelled, so it is carried through rather than skipped.
        long tsMillis = -1;

        /** First non-null of the fixed-point spellings, then the legacy form. */
        List<PriceLevel> yesLevels() {
            return firstNonNull(yesDollarsFp, yesDollars, yesLegacy);
        }

        List<PriceLevel> noLevels() {
            return firstNonNull(noDollarsFp, noDollars, noLegacy);
        }

        @SafeVarargs
        private static <T> T firstNonNull(T... candidates) {
            for (T c : candidates) {
                if (c != null) {
                    return c;
                }
            }
            return null;
        }
    }

    /**
     * Decodes one complete text frame.
     *
     * @throws IllegalArgumentException if the JSON is malformed or a book frame carries neither
     *                                a fixed-point nor a legacy representation
     */
    public WsFrame decode(String json) {
        try (JsonParser p = FACTORY.createParser(json)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("frame is not a JSON object: " + preview(json));
            }

            String type = null;
            long sid = -1;
            long seq = -1;
            long commandId = -1;
            Msg msg = null;

            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                p.nextToken();
                switch (field) {
                    case "type" -> type = p.getValueAsString();
                    case "sid" -> sid = p.getValueAsLong();
                    case "seq" -> seq = p.getValueAsLong();
                    case "id" -> commandId = p.getValueAsLong();
                    case "msg" -> msg = readMsg(p);
                    default -> p.skipChildren();
                }
            }

            if (type == null) {
                throw new IllegalArgumentException("frame has no type field: " + preview(json));
            }
            return assemble(type, sid, seq, commandId, msg, json);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot parse WS frame: " + preview(json), e);
        }
    }

    private WsFrame assemble(
            String type, long sid, long seq, long commandId, Msg msg, String json) {
        switch (type) {
            case "orderbook_snapshot" -> {
                Msg m = require(msg, type, json);
                // sid/seq sometimes live in the envelope and sometimes in msg; prefer the
                // envelope and fall back, rather than requiring one shape.
                long effectiveSid = sid >= 0 ? sid : (m.hasSid ? m.sid : -1);
                List<PriceLevel> yes = m.yesLevels();
                List<PriceLevel> no = m.noLevels();
                if (yes == null && no == null) {
                    throw new IllegalArgumentException(
                            "orderbook_snapshot has no recognised level fields (tried "
                                    + "yes_dollars_fp/no_dollars_fp, yes_dollars/no_dollars, "
                                    + "yes/no): " + preview(json));
                }
                return new WsFrame.Snapshot(
                        effectiveSid, seq, tickerOf(m, type, json),
                        yes == null ? List.of() : yes,
                        no == null ? List.of() : no,
                        m.tsMillis);
            }
            case "orderbook_delta" -> {
                Msg m = require(msg, type, json);
                long effectiveSid = sid >= 0 ? sid : (m.hasSid ? m.sid : -1);
                if (m.side == null) {
                    throw new IllegalArgumentException(
                            "orderbook_delta has no side: " + preview(json));
                }
                Price price;
                if (m.priceDollarsFp != null) {
                    price = Price.fromFixedPointString(m.priceDollarsFp);
                } else if (m.priceDollars != null) {
                    price = Price.fromFixedPointString(m.priceDollars);
                } else if (m.hasLegacyPrice) {
                    // Legacy prices are whole cents, so this branch cannot represent a
                    // sub-penny level. Reaching it on a live feed means precision is being lost.
                    price = Price.ofCents(m.legacyPriceCents);
                } else {
                    throw new IllegalArgumentException(
                            "orderbook_delta has no recognised price field (tried "
                                    + "price_dollars_fp, price_dollars, price): "
                                    + preview(json));
                }
                long deltaMicros;
                if (m.deltaFp != null) {
                    // Signed: a negative delta is a cancel, fill, or expiry.
                    deltaMicros = FixedPoint.parseMicros(m.deltaFp);
                } else if (m.hasLegacyDelta) {
                    // Legacy counts are whole contracts.
                    deltaMicros = Math.multiplyExact(m.legacyDeltaContracts, FixedPoint.ONE);
                } else {
                    throw new IllegalArgumentException(
                            "orderbook_delta has no recognised size field (tried delta_fp, "
                                    + "delta_dollars_fp, delta): " + preview(json));
                }
                return new WsFrame.Delta(
                        effectiveSid, seq, tickerOf(m, type, json),
                        Side.fromWireName(m.side), price, deltaMicros, m.tsMillis);
            }
            case "subscribed" -> {
                long effectiveSid = sid >= 0 ? sid : (msg != null && msg.hasSid ? msg.sid : -1);
                return new WsFrame.Subscribed(
                        commandId, effectiveSid, msg == null ? null : msg.channel);
            }
            case "ok" -> {
                long effectiveSid = sid >= 0 ? sid : (msg != null && msg.hasSid ? msg.sid : -1);
                return new WsFrame.Ok(commandId, effectiveSid, seq);
            }
            case "error" -> {
                long effectiveSid = sid >= 0 ? sid : (msg != null && msg.hasSid ? msg.sid : -1);
                return new WsFrame.ErrorFrame(
                        commandId, effectiveSid, seq,
                        msg == null ? null : msg.code,
                        msg == null ? null : msg.message);
            }
            default -> {
                return new WsFrame.Unhandled(type);
            }
        }
    }

    private static Msg require(Msg msg, String type, String json) {
        if (msg == null) {
            throw new IllegalArgumentException(type + " has no msg object: " + preview(json));
        }
        return msg;
    }

    private static MarketTicker tickerOf(Msg m, String type, String json) {
        if (m.marketTicker == null || m.marketTicker.isBlank()) {
            throw new IllegalArgumentException(
                    type + " has no market_ticker: " + preview(json));
        }
        return MarketTicker.of(m.marketTicker);
    }

    private Msg readMsg(JsonParser p) throws IOException {
        if (p.currentToken() != JsonToken.START_OBJECT) {
            p.skipChildren();
            return null;
        }
        Msg m = new Msg();
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "market_ticker" -> m.marketTicker = p.getValueAsString();
                case "channel" -> m.channel = p.getValueAsString();
                case "side" -> m.side = p.getValueAsString();
                case "sid" -> {
                    m.sid = p.getValueAsLong();
                    m.hasSid = true;
                }
                case "ts_ms" -> m.tsMillis = p.getValueAsLong(-1L);
                case "price_dollars_fp" -> m.priceDollarsFp = p.getValueAsString();
                case "price_dollars" -> m.priceDollars = p.getValueAsString();
                case "delta_fp", "delta_dollars_fp" -> m.deltaFp = p.getValueAsString();
                // Observed live on orderbook_snapshot.
                case "yes_dollars_fp" -> m.yesDollarsFp = readFixedPointLevels(p);
                case "no_dollars_fp" -> m.noDollarsFp = readFixedPointLevels(p);
                case "yes_dollars" -> m.yesDollars = readFixedPointLevels(p);
                case "no_dollars" -> m.noDollars = readFixedPointLevels(p);
                case "price" -> {
                    m.legacyPriceCents = p.getValueAsLong();
                    m.hasLegacyPrice = true;
                }
                case "delta" -> {
                    m.legacyDeltaContracts = p.getValueAsLong();
                    m.hasLegacyDelta = true;
                }
                case "yes" -> m.yesLegacy = readLegacyLevels(p);
                case "no" -> m.noLegacy = readLegacyLevels(p);
                // Kalshi's error payload nests the human text under "msg" again.
                case "code" -> m.code = p.getValueAsString();
                case "message", "msg" -> m.message = p.getValueAsString();
                default -> p.skipChildren();
            }
        }
        return m;
    }

    /** {@code [["0.4500","100.00"], ...]} */
    private static List<PriceLevel> readFixedPointLevels(JsonParser p) throws IOException {
        if (p.currentToken() != JsonToken.START_ARRAY) {
            p.skipChildren();
            return List.of();
        }
        List<PriceLevel> levels = new ArrayList<>();
        while (p.nextToken() == JsonToken.START_ARRAY) {
            p.nextToken();
            String price = p.getValueAsString();
            p.nextToken();
            String count = p.getValueAsString();
            // Tolerate extra elements rather than assuming exactly two.
            while (p.nextToken() != JsonToken.END_ARRAY) {
                p.skipChildren();
            }
            levels.add(PriceLevel.fromFixedPointStrings(price, count));
        }
        return levels;
    }

    /** Legacy {@code [[45, 100], ...]} — whole cents, whole contracts. */
    private static List<PriceLevel> readLegacyLevels(JsonParser p) throws IOException {
        if (p.currentToken() != JsonToken.START_ARRAY) {
            p.skipChildren();
            return List.of();
        }
        List<PriceLevel> levels = new ArrayList<>();
        while (p.nextToken() == JsonToken.START_ARRAY) {
            p.nextToken();
            long cents = p.getValueAsLong();
            p.nextToken();
            long contracts = p.getValueAsLong();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                p.skipChildren();
            }
            levels.add(new PriceLevel(Price.ofCents(cents), Quantity.ofContracts(contracts)));
        }
        return levels;
    }

    private static String preview(String json) {
        if (json == null) {
            return "<null>";
        }
        return json.length() <= 300 ? json : json.substring(0, 300) + "... (truncated)";
    }
}
