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
    private final Map<Long, List<WsFrame.Delta>> buffers = new HashMap<>();
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
        List<WsFrame.Delta> buffer = buffers.get(sid);
        return buffer == null ? 0 : buffer.size();
    }

    /** Offers one sequenced frame and returns the action to take. */
    public Outcome onFrame(WsFrame.Sequenced frame) {
        return frame instanceof WsFrame.Snapshot snapshot
                ? onSnapshot(snapshot)
                : onDelta((WsFrame.Delta) frame);
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
        List<WsFrame.Delta> buffer = buffers.remove(sid);

        int discardedStale = 0;
        List<WsFrame.Delta> replay = List.of();
        boolean stillGapped = false;
        int abandoned = 0;

        if (buffer != null && !buffer.isEmpty()) {
            buffer.sort(Comparator.comparingLong(WsFrame.Delta::seq));

            List<WsFrame.Delta> above = new ArrayList<>(buffer.size());
            for (WsFrame.Delta d : buffer) {
                // The discard rule. Anything at or below the snapshot's seq is already
                // reflected in it; replaying would apply the same delta twice.
                if (d.seq() <= snapshot.seq()) {
                    discardedStale++;
                } else {
                    above.add(d);
                }
            }

            // Only a contiguous run starting at snapshot.seq + 1 is safe to replay. If the
            // buffer itself has a hole -- entirely possible, since the frames that caused the
            // original gap may still be missing -- replaying past it would silently skip
            // updates. Stop at the hole and ask for another snapshot.
            List<WsFrame.Delta> contiguous = new ArrayList<>(above.size());
            long expected = snapshot.seq() + 1;
            int i = 0;
            for (; i < above.size(); i++) {
                WsFrame.Delta d = above.get(i);
                if (d.seq() == expected) {
                    contiguous.add(d);
                    expected++;
                } else if (d.seq() < expected) {
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
        }

        long newBaseline = replay.isEmpty()
                ? snapshot.seq()
                : replay.get(replay.size() - 1).seq();
        sequences.resetTo(sid, newBaseline);

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
                long expected = sequences.lastSeq(sid) + 1;
                long missing = sequences.gapSize(sid, delta.seq());

                // Clear the buffer, exactly as the algorithm specifies, then hold the frame
                // that exposed the gap: it is a delta we have not applied, and its seq may well
                // be above the incoming snapshot's.
                List<WsFrame.Delta> previous = buffers.remove(sid);
                int discarded = previous == null ? 0 : previous.size();

                states.put(sid, State.AWAITING_SNAPSHOT);
                buffer(delta);

                metrics.gapDetected(missing);
                metrics.snapshotRequested();
                return new Outcome.RequestSnapshot(
                        sid, expected, delta.seq(), missing, discarded);
            }
            default -> throw new IllegalStateException("unhandled verdict " + verdict);
        }
    }

    private void buffer(WsFrame.Delta delta) {
        List<WsFrame.Delta> buffer =
                buffers.computeIfAbsent(delta.sid(), k -> new ArrayList<>());
        if (buffer.size() >= maxBufferedPerSid) {
            // Drop the oldest: a snapshot will supersede old deltas anyway, and the recent tail
            // is what still needs replaying.
            buffer.remove(0);
            metrics.bufferOverflowDropped();
        }
        buffer.add(delta);
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
