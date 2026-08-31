package com.kalshiarb.kalshi.ws;

/**
 * One complete text frame, with the local receive time captured as early as possible.
 *
 * <p>The timestamps are taken on the WebSocket callback thread the moment the final fragment
 * arrives, before any parsing. That ordering is the whole point: the recorded receive time is
 * the basis for the latency analysis the project exists to produce, so it must not include our
 * own decode cost.
 *
 * <p>Both readings are kept because they answer different questions.
 * {@code receivedAtMillis} is wall time, needed to correlate a frame with exchange timestamps
 * and to name the archive file it belongs in. {@code receivedAtNanos} is monotonic, and is the
 * only one of the two safe for measuring an interval — wall time can step backwards under NTP
 * correction, which would produce negative latencies in the analysis.
 *
 * @param json             the complete frame text, exactly as received
 * @param receivedAtMillis wall-clock receive time
 * @param receivedAtNanos  monotonic receive time
 */
public record RawFrame(String json, long receivedAtMillis, long receivedAtNanos) {

    public int sizeBytes() {
        return json.length();
    }
}
