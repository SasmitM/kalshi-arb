package com.kalshiarb.kalshi.ws;

/**
 * Callbacks from the WebSocket layer, invoked on the pump thread — never on the WebSocket's own
 * callback thread.
 *
 * <p>Implementations may do real work here (write to disk, update books) because the pump thread
 * is ours to block. Blocking the WebSocket callback thread, by contrast, stalls the whole
 * connection including its automatic Pong replies, which is why the hand-off queue exists.
 */
public interface WsListener {

    /**
     * A raw frame arrived, with its receive timestamps.
     *
     * <p>Called before decoding, so the recorder can archive bytes it does not understand.
     */
    default void onRawFrame(RawFrame frame) {
    }

    /** A frame was decoded. */
    default void onFrame(WsFrame frame, RawFrame raw) {
    }

    /**
     * The recovery machine produced an action for a sequenced frame.
     *
     * <p>This is where a book layer plugs in: apply, buffer, rebuild, or ignore.
     */
    default void onRecoveryOutcome(SnapshotRecovery.Outcome outcome) {
    }

    /** Connection state changed. */
    default void onStateChange(String from, String to, String reason) {
    }

    /** A frame could not be decoded. The connection is left running. */
    default void onDecodeFailure(RawFrame frame, Exception cause) {
    }
}
