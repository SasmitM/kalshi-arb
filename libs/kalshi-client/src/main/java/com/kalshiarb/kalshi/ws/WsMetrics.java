package com.kalshiarb.kalshi.ws;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters for the WebSocket layer.
 *
 * <p>Plain {@link AtomicLong}s rather than Micrometer: metrics infrastructure is explicitly
 * deferred, and these are read across the callback thread, the pump thread and whatever prints
 * them, so they do need to be atomic.
 *
 * <p>{@link #framesDropped()} is the one that matters most. The WebSocket callback thread must
 * never block, so a full hand-off queue drops the frame and lets sequence-gap recovery rebuild.
 * That is a correct design and a silent one — without this counter, a system under load looks
 * healthy while quietly resnapshotting constantly.
 */
public final class WsMetrics {

    private final AtomicLong framesReceived = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    private final AtomicLong framesDropped = new AtomicLong();
    private final AtomicLong decodeFailures = new AtomicLong();
    private final AtomicLong gapsDetected = new AtomicLong();
    private final AtomicLong framesMissing = new AtomicLong();
    private final AtomicLong snapshotsRequested = new AtomicLong();
    private final AtomicLong recoveries = new AtomicLong();
    private final AtomicLong duplicatesDropped = new AtomicLong();
    private final AtomicLong bufferOverflowDrops = new AtomicLong();
    private final AtomicLong reconnects = new AtomicLong();
    private final AtomicLong pingsReceived = new AtomicLong();

    void frameReceived(int bytes) {
        framesReceived.incrementAndGet();
        bytesReceived.addAndGet(bytes);
    }

    void frameDropped() {
        framesDropped.incrementAndGet();
    }

    void decodeFailure() {
        decodeFailures.incrementAndGet();
    }

    void gapDetected(long missing) {
        gapsDetected.incrementAndGet();
        framesMissing.addAndGet(missing);
    }

    void snapshotRequested() {
        snapshotsRequested.incrementAndGet();
    }

    void recovered() {
        recoveries.incrementAndGet();
    }

    void duplicateDropped() {
        duplicatesDropped.incrementAndGet();
    }

    void bufferOverflowDropped() {
        bufferOverflowDrops.incrementAndGet();
    }

    void reconnected() {
        reconnects.incrementAndGet();
    }

    void pingReceived() {
        pingsReceived.incrementAndGet();
    }

    public long framesReceived() {
        return framesReceived.get();
    }

    public long bytesReceived() {
        return bytesReceived.get();
    }

    /** Frames discarded because the hand-off queue was full. Should stay at zero. */
    public long framesDropped() {
        return framesDropped.get();
    }

    public long decodeFailures() {
        return decodeFailures.get();
    }

    public long gapsDetected() {
        return gapsDetected.get();
    }

    public long framesMissing() {
        return framesMissing.get();
    }

    public long snapshotsRequested() {
        return snapshotsRequested.get();
    }

    public long recoveries() {
        return recoveries.get();
    }

    public long duplicatesDropped() {
        return duplicatesDropped.get();
    }

    public long bufferOverflowDrops() {
        return bufferOverflowDrops.get();
    }

    public long reconnects() {
        return reconnects.get();
    }

    public long pingsReceived() {
        return pingsReceived.get();
    }

    @Override
    public String toString() {
        return "frames=" + framesReceived()
                + " bytes=" + bytesReceived()
                + " dropped=" + framesDropped()
                + " decodeFail=" + decodeFailures()
                + " gaps=" + gapsDetected()
                + " missing=" + framesMissing()
                + " snapshotReq=" + snapshotsRequested()
                + " recoveries=" + recoveries()
                + " dupes=" + duplicatesDropped()
                + " bufOverflow=" + bufferOverflowDrops()
                + " reconnects=" + reconnects()
                + " pings=" + pingsReceived();
    }
}
