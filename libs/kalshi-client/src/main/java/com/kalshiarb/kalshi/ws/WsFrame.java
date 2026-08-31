package com.kalshiarb.kalshi.ws;

import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.Price;
import com.kalshiarb.model.Side;
import java.util.List;

/**
 * A decoded Kalshi WebSocket frame.
 *
 * <p>Split into {@link Sequenced} and everything else, because {@code seq} is the field the
 * recovery machinery turns on and only book frames carry it. Making that a type distinction
 * rather than a {@code -1} sentinel means {@link SequenceTracker} cannot be handed a
 * {@code subscribed} frame by accident.
 *
 * <p><b>{@code seq} is scoped to the subscription ({@code sid}), not to the market.</b> One
 * dropped message therefore invalidates every book on that subscription, which is the entire
 * reason subscriptions are sharded by event family.
 */
public sealed interface WsFrame {

    /** The raw {@code type} string, retained so unknown frames can be logged meaningfully. */
    String type();

    /**
     * A frame carrying a subscription-scoped sequence number.
     *
     * <p>Both a snapshot and a delta advance the same counter, so recovery has to reason about
     * them together rather than treating snapshots as out-of-band.
     */
    sealed interface Sequenced extends WsFrame {
        long sid();

        long seq();

        MarketTicker ticker();
    }

    /**
     * Full book state for one market. Sent once per market on subscribe, and again on request
     * via {@code update_subscription} with {@code action: "get_snapshot"}.
     *
     * @param sid     subscription id
     * @param seq     sequence number this snapshot is current as of
     * @param ticker  the market
     * @param yesBids resting YES bids
     * @param noBids  resting NO bids
     */
    record Snapshot(
            long sid, long seq, MarketTicker ticker,
            List<com.kalshiarb.model.PriceLevel> yesBids,
            List<com.kalshiarb.model.PriceLevel> noBids) implements Sequenced {

        public Snapshot {
            yesBids = List.copyOf(yesBids);
            noBids = List.copyOf(noBids);
        }

        @Override
        public String type() {
            return "orderbook_snapshot";
        }
    }

    /**
     * A change to one price level.
     *
     * <p><b>The delta is a change, not a new absolute value</b> — it must be added to the
     * existing quantity at that price. It may be negative (a cancel, a fill, or an expiry; the
     * protocol does not distinguish, and for book maintenance it does not matter). A level
     * reaching zero is removed rather than kept as a zero entry; a level going negative is a
     * bug and must throw rather than clamp.
     *
     * @param sid                 subscription id
     * @param seq                 sequence number
     * @param ticker              the market
     * @param side                which bid book the level lives on
     * @param price               the level price
     * @param deltaMicroContracts signed size change, in micro-contracts
     */
    record Delta(
            long sid, long seq, MarketTicker ticker, Side side, Price price,
            long deltaMicroContracts) implements Sequenced {

        @Override
        public String type() {
            return "orderbook_delta";
        }

        public boolean isRemoval() {
            return deltaMicroContracts < 0;
        }
    }

    /**
     * Acknowledgement of a subscribe command, carrying the {@code sid} that every subsequent
     * frame on that subscription will use.
     *
     * @param commandId the {@code id} echoed from our command
     * @param sid       the assigned subscription id
     * @param channel   the subscribed channel
     */
    record Subscribed(long commandId, long sid, String channel) implements WsFrame {
        @Override
        public String type() {
            return "subscribed";
        }
    }

    /**
     * A command-level error.
     *
     * @param commandId the {@code id} echoed from our command, or -1
     * @param code      Kalshi error code, as exact wire text
     * @param message   human message
     */
    record ErrorFrame(long commandId, String code, String message) implements WsFrame {
        @Override
        public String type() {
            return "error";
        }
    }

    /**
     * Acknowledgement of a command other than the first {@code subscribe} on a channel.
     *
     * <p>Observed live: the first {@code subscribe} for a channel is answered with
     * {@code subscribed}, and every later {@code subscribe} or {@code update_subscription} on
     * that channel is answered with {@code ok}.
     *
     * <p>{@link #seq} is captured because {@code ok} frames appear to sit <b>inside</b> the
     * sequence stream — a live run showed snapshot seqs 1-6, 8-13, 15-20, 22-27 with exactly
     * three {@code ok} frames, which accounts for the missing 7, 14 and 21. If that holds,
     * treating {@code ok} as unsequenced makes {@link SequenceTracker} report a false gap at
     * every subscribe ack. Left un-wired into recovery until the raw frame confirms it, rather
     * than guessing twice.
     *
     * @param commandId the {@code id} echoed from our command, or -1
     * @param sid       the subscription this concerns, or -1
     * @param seq       the sequence number if the frame carries one, else -1
     */
    record Ok(long commandId, long sid, long seq) implements WsFrame {

        @Override
        public String type() {
            return "ok";
        }

        public boolean hasSeq() {
            return seq >= 0;
        }
    }

    /**
     * Any frame this client does not model.
     *
     * <p>Deliberately not an exception: Kalshi adding a frame type must not take the recorder
     * down, and the raw JSON is archived regardless of whether we understand it.
     *
     * @param type the unrecognised {@code type} value
     */
    record Unhandled(String type) implements WsFrame {
    }
}
