package com.kalshiarb.persistence;

import com.kalshiarb.model.EventTicker;

/**
 * One archived frame: the raw WebSocket text plus the provenance needed to replay it.
 *
 * <p>The raw JSON is stored <b>verbatim</b>, not re-serialised from a decoded object. Two
 * reasons, and both matter:
 *
 * <ul>
 *   <li>A frame we could not decode is still worth keeping. The {@code yes_dollars_fp} mistake
 *       would have produced a day of archives full of frames this codebase rejected; storing
 *       raw text means such an archive stays fully usable once the codec is fixed.
 *   <li>Re-serialising bakes today's understanding of the protocol into the permanent record.
 *       The archive should outlive the parser.
 * </ul>
 *
 * @param family           event family whose socket delivered the frame
 * @param json             the frame, byte-for-byte as received
 * @param receivedAtMillis local wall-clock receive time
 * @param receivedAtNanos  local monotonic receive time, comparable only within one process run
 */
public record ArchivedFrame(
        EventTicker family, String json, long receivedAtMillis, long receivedAtNanos) {

    /**
     * Schema version, written on every line.
     *
     * <p>Cheap now, and the alternative is guessing later. A recording made today has to remain
     * readable after the archive format changes, because recorded market data cannot be
     * regenerated.
     */
    public static final int SCHEMA_VERSION = 1;
}
