package com.kalshiarb.kalshi.ws;

import com.kalshiarb.model.EventTicker;

/**
 * One complete text frame, with its origin and the local receive time captured as early as
 * possible.
 *
 * <p>The timestamps are taken on the WebSocket callback thread the moment the final fragment
 * arrives, before any parsing. That ordering is the whole point: the recorded receive time is
 * the basis for the latency analysis the project exists to produce, so it must not include our
 * own decode cost.
 *
 * <p>Both readings are kept because they answer different questions.
 * {@code receivedAtMillis} is wall time, needed to pair against the exchange's own
 * {@code ts_ms} and to decide which daily archive file a frame belongs in.
 * {@code receivedAtNanos} is monotonic, and is the only one of the two safe for measuring an
 * interval — wall time can step backwards under NTP correction, which would produce negative
 * latencies in the analysis.
 *
 * <p>{@code family} records which connection delivered the frame. With one socket per event
 * family, a frame's origin is not recoverable from its own contents alone once several streams
 * are multiplexed into a single archive file, and a replay has to be able to reconstruct the
 * per-shard sequence streams separately.
 *
 * @param family           the event family whose socket delivered this frame
 * @param json             the complete frame text, exactly as received
 * @param receivedAtMillis wall-clock receive time
 * @param receivedAtNanos  monotonic receive time
 */
public record RawFrame(
        EventTicker family, String json, long receivedAtMillis, long receivedAtNanos) {

    public int sizeBytes() {
        return json.length();
    }
}
