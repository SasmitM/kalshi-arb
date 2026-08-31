package com.kalshiarb.kalshi.ws;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The buffer-and-discard gap recovery state machine from CLAUDE.md section 5, implemented as
 * written:
 *
 * <pre>{@code
 * on gap (seq != last_seq + 1):
 *     state = BUFFERING; buffer.clear()
 *     send update_subscription(sid, action="get_snapshot")
 * while BUFFERING:
 *     on delta    -> buffer.add(delta)          // hold, do not apply
 *     on snapshot -> book = build(snapshot)
 *                    for d in buffer:
 *                        if d.seq > snapshot.seq: book.apply(d)
 *                    state = STREAMING
 * }</pre>
 *
 * <p>Two properties are load-bearing:
 *
 * <ul>
 *   <li><b>The connection is not torn down.</b> A gap is recovered in-place by asking for a new
 *       snapshot on the existing socket. Reconnecting would drop every other shard's books too.
 *   <li><b>Buffered deltas at or below {@code snapshot.seq} are discarded.</b> They are already
 *       baked into the snapshot, so replaying them would double-count. This is the single
 *       easiest way to corrupt a book while appearing to recover cleanly.
 * </ul>
 *
 * <p>This class decides <em>what</em> to do and never touches a book, so it is pure and fully
 * testable without any book implementation — which is also what will let the replay engine drive
 * identical logic later. Callers act on the returned {@link Outcome}.
 *
 * <p>Not thread-safe: one instance per shard, driven by that shard's single thread.
 */
public final class SnapshotRecovery {

    /** Per-subscription recovery state. */
    public enum State {
        /**
         * No usable baseline: either we just subscribed, or a gap invalidated the books and we
         * are waiting for the replacement snapshot. Deltas are held, not applied.
         */
        AWAITING_SNAPSHOT,
        /** Baseline established; in-order deltas apply directly. */
        STREAMING
    }

    /** What the caller should do with the frame just offered. */
    public sealed interface Outcome {

        /** Apply this frame to the book immediately. */
        record Apply(WsFrame.Sequenced frame) implements Outcome {
        }

        /** Frame held pending a snapshot. Do not apply it. */
        record Buffered(long sid, long seq, int bufferDepth) implements Outcome {
        }

        /**
         * A gap was detected. Send {@code update_subscription} with
         * {@code action: "get_snapshot"} for this sid. Do not reconnect.
         *
         * @param sid          the affected subscription
         * @param expectedSeq  the seq we wanted
         * @param observedSeq  the seq that arrived
         * @param missingCount how many frames were lost
         * @param discarded    buffered frames thrown away when the buffer was cleared
         */
        record RequestSnapshot(
                long sid, long expectedSeq, long observedSeq, long missingCount, int discarded)
                implements Outcome {
        }

        /**
         * Rebuild the books for this sid from {@code snapshot}, then apply {@code replay} in
         * order.
         *
         * @param snapshot          the authoritative new state
         * @param replay            buffered deltas with {@code seq > snapshot.seq}, contiguous
         *                          and in ascending order
         * @param discardedStale    buffered deltas at or below {@code snapshot.seq}, dropped
         *                          because the snapshot already contains them
         * @param stillGapped       true if the buffer had an internal hole above the snapshot,
         *                          so another snapshot is required; the caller must send a
         *                          further {@code get_snapshot}
         * @param abandonedAfterGap buffered deltas past that internal hole, dropped
         */
        record Rebuild(
                WsFrame.Snapshot snapshot,
                List<WsFrame.Delta> replay,
                int discardedStale,
                boolean stillGapped,
                int abandonedAfterGap) implements Outcome {
        }

        /**
         * A control frame occupied a sequence number. The counter advanced; there is nothing to
         * apply to any book.
         */
        record SequenceOnly(long sid, long seq, String frameType) implements Outcome {
        }

        /** Frame ignored, with the reason. Duplicates land here. */
        record Ignored(long sid, long seq, String reason) implements Outcome {
        }
    }

    /**
     * Bound on held frames. A gap that never resolves must not grow the heap without limit; on
     * overflow the oldest frames go, since the newest are the ones a fresh snapshot will need.
     */
    private final int maxBufferedPerSid;

    private final SequenceTracker sequences;
    private final Map<Long, State> states = new HashMap<>();

    /**
     * Held frames, keyed by sid.
     *
     * <p>Holds every {@link WsFrame.Sequenced}, not just deltas. A control frame such as
     * {@code ok} consumes a sequence number without changing a book, so if the buffer tracked
     * only deltas the contiguity walk in {@link #onSnapshot} would see a hole wherever an
     * {@code ok} had been and declare a gap that does not exist.
     */
    private final Map<Long, List<WsFrame.Sequenced>> buffers = new HashMap<>();
    private final WsMetrics metrics;

    public SnapshotRecovery(SequenceTracker sequences, WsMetrics metrics, int maxBufferedPerSid) {
        this.sequences = sequences;
        this.metrics = metrics;
        if (maxBufferedPerSid <= 0) {
            throw new IllegalArgumentException("maxBufferedPerSid must be positive");
        }
        this.maxBufferedPerSid = maxBufferedPerSid;
    }

    public SnapshotRecovery(SequenceTracker sequences, WsMetrics metrics) {
        this(sequences, metrics, 10_000);
    }

    public State state(long sid) {
        return states.getOrDefault(sid, State.AWAITING_SNAPSHOT);
    }

    public int bufferDepth(long sid) {
        List<WsFrame.Sequenced> buffer = buffers.get(sid);
        return buffer == null ? 0 : buffer.size();
    }

    /** Offers one sequenced frame and returns the action to take. */
    public Outcome onFrame(WsFrame.Sequenced frame) {
        return switch (frame) {
            case WsFrame.Snapshot snapshot -> onSnapshot(snapshot);
            case WsFrame.Delta delta -> onDelta(delta);
            // Control frames: they hold a seq but touch no book.
            case WsFrame.Ok ok -> onControl(ok);
        };
    }

    /**
     * A sequenced frame that is not a book change.
     *
     * <p>It still has to advance the counter. Skipping it would make the next real delta look
     * like it had skipped a frame.
     */
    private Outcome onControl(WsFrame.Sequenced control) {
        long sid = control.sid();

        if (state(sid) == State.AWAITING_SNAPSHOT) {
            // Buffered as a marker only. It will never be replayed, but it keeps the
            // contiguity walk honest about which sequence numbers are accounted for.
            buffer(control);
            return new Outcome.Buffered(sid, control.seq(), bufferDepth(sid));
        }

        SequenceTracker.Verdict verdict = sequences.observe(sid, control.seq());
        return switch (verdict) {
            case IN_ORDER, FIRST ->
                    new Outcome.SequenceOnly(sid, control.seq(), control.type());
            case DUPLICATE -> {
                metrics.duplicateDropped();
                yield new Outcome.Ignored(sid, control.seq(),
                        "control frame at or below last " + sequences.lastSeq(sid));
            }
            case GAP -> beginRecovery(control);
        };
    }

    /**
     * A snapshot always wins.
     *
     * <p>It is authoritative whether we asked for it or not, so it is handled identically in
     * both states rather than being treated as unexpected while streaming. Kalshi can re-snapshot
     * on its own, and second-guessing that would leave us running on staler state than the server
     * just handed us.
     */
    private Outcome onSnapshot(WsFrame.Snapshot snapshot) {
        long sid = snapshot.sid();
        List<WsFrame.Sequenced> buffer = buffers.remove(sid);

        int discardedStale = 0;
        List<WsFrame.Delta> replay = List.of();
        boolean stillGapped = false;
        int abandoned = 0;
        long baseline = snapshot.seq();

        if (buffer != null && !buffer.isEmpty()) {
            buffer.sort(Comparator.comparingLong(WsFrame.Sequenced::seq));

            List<WsFrame.Sequenced> above = new ArrayList<>(buffer.size());
            for (WsFrame.Sequenced f : buffer) {
                // The discard rule. Anything at or below the snapshot's seq is already
                // reflected in it; replaying would apply the same delta twice.
                if (f.seq() <= snapshot.seq()) {
                    discardedStale++;
                } else {
                    above.add(f);
                }
            }

            // Only a contiguous run starting at snapshot.seq + 1 is safe to replay. If the
            // buffer itself has a hole -- entirely possible, since the frames that caused the
            // original gap may still be missing -- replaying past it would silently skip
            // updates. Stop at the hole and ask for another snapshot.
            //
            // A control frame at the expected seq advances the run without contributing
            // anything to replay: the number is accounted for, so it is not a hole.
            List<WsFrame.Delta> contiguous = new ArrayList<>(above.size());
            long expected = snapshot.seq() + 1;
            int i = 0;
            for (; i < above.size(); i++) {
                WsFrame.Sequenced f = above.get(i);
                if (f.seq() == expected) {
                    if (f instanceof WsFrame.Delta d) {
                        contiguous.add(d);
                    }
                    expected++;
                } else if (f.seq() < expected) {
                    discardedStale++;
                } else {
                    break;
                }
            }
            if (i < above.size()) {
                stillGapped = true;
                abandoned = above.size() - i;
            }
            replay = contiguous;
            // expected - 1 rather than the last replayed delta's seq: a trailing control frame
            // consumed a number too, and the baseline has to include it.
            baseline = expected - 1;
        }

        sequences.resetTo(sid, baseline);

        if (stillGapped) {
            // Straight back into recovery: we have a fresh book but a known hole ahead.
            states.put(sid, State.AWAITING_SNAPSHOT);
            metrics.snapshotRequested();
        } else {
            states.put(sid, State.STREAMING);
        }
        metrics.recovered();

        return new Outcome.Rebuild(snapshot, replay, discardedStale, stillGapped, abandoned);
    }

    private Outcome onDelta(WsFrame.Delta delta) {
        long sid = delta.sid();

        if (state(sid) == State.AWAITING_SNAPSHOT) {
            buffer(delta);
            return new Outcome.Buffered(sid, delta.seq(), bufferDepth(sid));
        }

        SequenceTracker.Verdict verdict = sequences.observe(sid, delta.seq());
        switch (verdict) {
            case IN_ORDER, FIRST -> {
                return new Outcome.Apply(delta);
            }
            case DUPLICATE -> {
                metrics.duplicateDropped();
                return new Outcome.Ignored(sid, delta.seq(),
                        "seq " + delta.seq() + " at or below last " + sequences.lastSeq(sid));
            }
            case GAP -> {
                return beginRecovery(delta);
            }
            default -> throw new IllegalStateException("unhandled verdict " + verdict);
        }
    }

    /**
     * Enters recovery for the sid that {@code trigger} arrived on.
     *
     * <p>Shared by deltas and control frames, since either can be the frame that exposes a gap.
     */
    private Outcome beginRecovery(WsFrame.Sequenced trigger) {
        long sid = trigger.sid();
        long expected = sequences.lastSeq(sid) + 1;
        long missing = sequences.gapSize(sid, trigger.seq());

        // Clear the buffer, exactly as the algorithm specifies, then hold the frame that
        // exposed the gap: it is unapplied data whose seq may well be above the incoming
        // snapshot's.
        List<WsFrame.Sequenced> previous = buffers.remove(sid);
        int discarded = previous == null ? 0 : previous.size();

        states.put(sid, State.AWAITING_SNAPSHOT);
        buffer(trigger);

        metrics.gapDetected(missing);
        metrics.snapshotRequested();
        return new Outcome.RequestSnapshot(sid, expected, trigger.seq(), missing, discarded);
    }

    private void buffer(WsFrame.Sequenced frame) {
        List<WsFrame.Sequenced> buffer =
                buffers.computeIfAbsent(frame.sid(), k -> new ArrayList<>());
        if (buffer.size() >= maxBufferedPerSid) {
            // Drop the oldest: a snapshot will supersede old deltas anyway, and the recent tail
            // is what still needs replaying.
            buffer.remove(0);
            metrics.bufferOverflowDropped();
        }
        buffer.add(frame);
    }

    /** Forces a sid back into recovery, e.g. after a reconnect invalidates all books. */
    public void invalidate(long sid) {
        states.put(sid, State.AWAITING_SNAPSHOT);
        buffers.remove(sid);
        sequences.forget(sid);
    }

    public void invalidateAll() {
        states.clear();
        buffers.clear();
        sequences.forgetAll();
    }
}
