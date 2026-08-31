package com.kalshiarb.kalshi.ws;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks the expected next {@code seq} per subscription.
 *
 * <p><b>{@code seq} is scoped to the {@code sid}, not to the market.</b> A single subscription
 * carrying six markets has one counter across all six, so one dropped frame invalidates every
 * book on that subscription — not just the book the missing frame belonged to. That is the whole
 * justification for sharding subscriptions by event family: it bounds the blast radius of a gap
 * to one constraint group.
 *
 * <p>Not thread-safe by design. Each shard is owned by exactly one thread, which is what lets
 * the book layer run without locks.
 */
public final class SequenceTracker {

    /** What a newly observed sequence number means. */
    public enum Verdict {
        /** First frame seen on this sid; nothing to compare against. */
        FIRST,
        /** Exactly {@code last + 1}. */
        IN_ORDER,
        /** Above {@code last + 1} — at least one frame was lost. */
        GAP,
        /**
         * At or below {@code last}. A retransmission or a frame that raced a snapshot; applying
         * it would double-count a delta, so it must be discarded rather than replayed.
         */
        DUPLICATE
    }

    private final Map<Long, Long> lastSeqBySid = new HashMap<>();

    /**
     * Classifies {@code seq} against the last one seen on {@code sid}, and advances the counter
     * when the frame is usable.
     *
     * <p>A {@link Verdict#GAP} deliberately does <b>not</b> advance the counter: recovery is
     * driven by a fresh snapshot, and the snapshot's own seq is what re-establishes the
     * baseline via {@link #resetTo}.
     */
    public Verdict observe(long sid, long seq) {
        Long last = lastSeqBySid.get(sid);
        if (last == null) {
            lastSeqBySid.put(sid, seq);
            return Verdict.FIRST;
        }
        if (seq == last + 1) {
            lastSeqBySid.put(sid, seq);
            return Verdict.IN_ORDER;
        }
        if (seq <= last) {
            return Verdict.DUPLICATE;
        }
        return Verdict.GAP;
    }

    /** Forces the baseline, used when a snapshot re-establishes state after a gap. */
    public void resetTo(long sid, long seq) {
        lastSeqBySid.put(sid, seq);
    }

    /** The last accepted seq on {@code sid}, or -1 if none. */
    public long lastSeq(long sid) {
        return lastSeqBySid.getOrDefault(sid, -1L);
    }

    /** How many frames are missing if {@code seq} arrives now. Zero when there is no gap. */
    public long gapSize(long sid, long seq) {
        Long last = lastSeqBySid.get(sid);
        if (last == null || seq <= last + 1) {
            return 0;
        }
        return seq - last - 1;
    }

    /** Drops all state for a sid, e.g. after unsubscribing or reconnecting. */
    public void forget(long sid) {
        lastSeqBySid.remove(sid);
    }

    public void forgetAll() {
        lastSeqBySid.clear();
    }
}
