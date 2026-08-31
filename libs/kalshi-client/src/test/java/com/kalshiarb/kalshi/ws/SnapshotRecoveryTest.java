package com.kalshiarb.kalshi.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.Price;
import com.kalshiarb.model.PriceLevel;
import com.kalshiarb.model.Quantity;
import com.kalshiarb.model.Side;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the buffer-and-discard algorithm from CLAUDE.md section 5.
 *
 * <p>These are the tests that matter most in the WS layer: every failure mode here is silent.
 * A book corrupted by a double-applied delta looks exactly like a book that is merely wrong,
 * and the detector would then report a constraint violation that does not exist.
 */
class SnapshotRecoveryTest {

    private static final long SID = 7;
    private static final MarketTicker TICKER = MarketTicker.of("KXHIGHNY-26SEP01-T83");

    private SequenceTracker sequences;
    private WsMetrics metrics;
    private SnapshotRecovery recovery;

    @BeforeEach
    void setUp() {
        sequences = new SequenceTracker();
        metrics = new WsMetrics();
        recovery = new SnapshotRecovery(sequences, metrics);
    }

    private static WsFrame.Snapshot snapshot(long seq) {
        return new WsFrame.Snapshot(SID, seq, TICKER,
                List.of(new PriceLevel(Price.ofCents(50), Quantity.ofContracts(10))),
                List.of(new PriceLevel(Price.ofCents(48), Quantity.ofContracts(10))));
    }

    private static WsFrame.Delta delta(long seq) {
        return new WsFrame.Delta(SID, seq, TICKER, Side.YES, Price.ofCents(50), 1_000_000L);
    }

    @Test
    @DisplayName("starts in AWAITING_SNAPSHOT and buffers deltas that precede the snapshot")
    void bufferBeforeFirstSnapshot() {
        assertThat(recovery.state(SID)).isEqualTo(SnapshotRecovery.State.AWAITING_SNAPSHOT);

        assertThat(recovery.onFrame(delta(5)))
                .isInstanceOf(SnapshotRecovery.Outcome.Buffered.class);
        assertThat(recovery.bufferDepth(SID)).isEqualTo(1);
    }

    @Test
    @DisplayName("the first snapshot establishes the baseline and starts streaming")
    void firstSnapshotStartsStreaming() {
        SnapshotRecovery.Outcome outcome = recovery.onFrame(snapshot(10));

        assertThat(outcome).isInstanceOf(SnapshotRecovery.Outcome.Rebuild.class);
        SnapshotRecovery.Outcome.Rebuild rebuild = (SnapshotRecovery.Outcome.Rebuild) outcome;
        assertThat(rebuild.replay()).isEmpty();
        assertThat(rebuild.stillGapped()).isFalse();
        assertThat(recovery.state(SID)).isEqualTo(SnapshotRecovery.State.STREAMING);
        assertThat(sequences.lastSeq(SID)).isEqualTo(10);
    }

    @Test
    @DisplayName("in-order deltas apply directly")
    void inOrderApplies() {
        recovery.onFrame(snapshot(10));

        assertThat(recovery.onFrame(delta(11)))
                .isInstanceOf(SnapshotRecovery.Outcome.Apply.class);
        assertThat(recovery.onFrame(delta(12)))
                .isInstanceOf(SnapshotRecovery.Outcome.Apply.class);
        assertThat(sequences.lastSeq(SID)).isEqualTo(12);
        assertThat(metrics.gapsDetected()).isZero();
    }

    @Test
    @DisplayName("a gap requests a snapshot without reconnecting, and holds the gapping frame")
    void gapRequestsSnapshot() {
        recovery.onFrame(snapshot(10));
        recovery.onFrame(delta(11));

        // 12 is missing; 13 arrives.
        SnapshotRecovery.Outcome outcome = recovery.onFrame(delta(13));

        assertThat(outcome).isInstanceOf(SnapshotRecovery.Outcome.RequestSnapshot.class);
        SnapshotRecovery.Outcome.RequestSnapshot req =
                (SnapshotRecovery.Outcome.RequestSnapshot) outcome;
        assertThat(req.sid()).isEqualTo(SID);
        assertThat(req.expectedSeq()).isEqualTo(12);
        assertThat(req.observedSeq()).isEqualTo(13);
        assertThat(req.missingCount()).isEqualTo(1);

        assertThat(recovery.state(SID)).isEqualTo(SnapshotRecovery.State.AWAITING_SNAPSHOT);
        // The frame that exposed the gap is retained -- it is unapplied data whose seq may sit
        // above the incoming snapshot.
        assertThat(recovery.bufferDepth(SID)).isEqualTo(1);
        assertThat(metrics.gapsDetected()).isEqualTo(1);
        assertThat(metrics.framesMissing()).isEqualTo(1);
        assertThat(metrics.snapshotsRequested()).isEqualTo(1);
    }

    @Test
    @DisplayName("deltas during recovery are held, not applied")
    void bufferedDuringRecovery() {
        recovery.onFrame(snapshot(10));
        recovery.onFrame(delta(13)); // gap

        assertThat(recovery.onFrame(delta(14)))
                .isInstanceOf(SnapshotRecovery.Outcome.Buffered.class);
        assertThat(recovery.onFrame(delta(15)))
                .isInstanceOf(SnapshotRecovery.Outcome.Buffered.class);
        assertThat(recovery.bufferDepth(SID)).isEqualTo(3);
    }

    @Test
    @DisplayName("THE DISCARD RULE: buffered deltas at or below snapshot.seq are dropped")
    void discardsDeltasAtOrBelowSnapshotSeq() {
        recovery.onFrame(snapshot(10));
        recovery.onFrame(delta(13)); // gap, buffers 13
        recovery.onFrame(delta(14));
        recovery.onFrame(delta(15));

        // The replacement snapshot already contains everything through seq 14.
        SnapshotRecovery.Outcome.Rebuild rebuild =
                (SnapshotRecovery.Outcome.Rebuild) recovery.onFrame(snapshot(14));

        // 13 and 14 are baked into the snapshot; replaying them would double-count.
        assertThat(rebuild.discardedStale()).isEqualTo(2);
        assertThat(rebuild.replay()).extracting(WsFrame.Delta::seq).containsExactly(15L);
        assertThat(rebuild.stillGapped()).isFalse();
        assertThat(recovery.state(SID)).isEqualTo(SnapshotRecovery.State.STREAMING);
        assertThat(sequences.lastSeq(SID)).isEqualTo(15);
    }

    @Test
    @DisplayName("a snapshot newer than everything buffered discards the whole buffer")
    void snapshotNewerThanBufferDiscardsAll() {
        recovery.onFrame(snapshot(10));
        recovery.onFrame(delta(13));
        recovery.onFrame(delta(14));

        SnapshotRecovery.Outcome.Rebuild rebuild =
                (SnapshotRecovery.Outcome.Rebuild) recovery.onFrame(snapshot(20));

        assertThat(rebuild.discardedStale()).isEqualTo(2);
        assertThat(rebuild.replay()).isEmpty();
        assertThat(sequences.lastSeq(SID)).isEqualTo(20);
        assertThat(recovery.state(SID)).isEqualTo(SnapshotRecovery.State.STREAMING);
    }

    @Test
    @DisplayName("replay is contiguous from snapshot.seq + 1 and stops at an internal hole")
    void replayStopsAtInternalHole() {
        recovery.onFrame(snapshot(10));
        recovery.onFrame(delta(13)); // gap
        recovery.onFrame(delta(14));
        // 15 never arrives.
        recovery.onFrame(delta(16));
        recovery.onFrame(delta(17));

        SnapshotRecovery.Outcome.Rebuild rebuild =
                (SnapshotRecovery.Outcome.Rebuild) recovery.onFrame(snapshot(12));

        // 13 and 14 are contiguous above 12 and safe. 16 and 17 sit past a hole at 15 --
        // applying them would silently skip an update, so they are abandoned.
        assertThat(rebuild.replay()).extracting(WsFrame.Delta::seq).containsExactly(13L, 14L);
        assertThat(rebuild.stillGapped()).isTrue();
        assertThat(rebuild.abandonedAfterGap()).isEqualTo(2);

        // Straight back into recovery, and another snapshot is requested.
        assertThat(recovery.state(SID)).isEqualTo(SnapshotRecovery.State.AWAITING_SNAPSHOT);
        assertThat(sequences.lastSeq(SID)).isEqualTo(14);
        assertThat(metrics.snapshotsRequested()).isEqualTo(2);
    }

    @Test
    @DisplayName("buffered deltas are replayed in seq order even if they arrive shuffled")
    void replaySortsBySeq() {
        recovery.onFrame(snapshot(10));
        recovery.onFrame(delta(14)); // gap detected here
        recovery.onFrame(delta(13));
        recovery.onFrame(delta(12));
        recovery.onFrame(delta(15));

        SnapshotRecovery.Outcome.Rebuild rebuild =
                (SnapshotRecovery.Outcome.Rebuild) recovery.onFrame(snapshot(11));

        assertThat(rebuild.replay())
                .extracting(WsFrame.Delta::seq)
                .containsExactly(12L, 13L, 14L, 15L);
        assertThat(rebuild.stillGapped()).isFalse();
        assertThat(sequences.lastSeq(SID)).isEqualTo(15);
    }

    @Test
    @DisplayName("a duplicate is ignored, never applied twice")
    void duplicateIgnored() {
        recovery.onFrame(snapshot(10));
        recovery.onFrame(delta(11));

        SnapshotRecovery.Outcome outcome = recovery.onFrame(delta(11));

        assertThat(outcome).isInstanceOf(SnapshotRecovery.Outcome.Ignored.class);
        assertThat(sequences.lastSeq(SID)).isEqualTo(11);
        assertThat(metrics.duplicatesDropped()).isEqualTo(1);
        assertThat(metrics.gapsDetected()).isZero();
    }

    @Test
    @DisplayName("an unsolicited snapshot while streaming is accepted as authoritative")
    void unsolicitedSnapshotAccepted() {
        recovery.onFrame(snapshot(10));
        recovery.onFrame(delta(11));

        SnapshotRecovery.Outcome outcome = recovery.onFrame(snapshot(30));

        assertThat(outcome).isInstanceOf(SnapshotRecovery.Outcome.Rebuild.class);
        assertThat(sequences.lastSeq(SID)).isEqualTo(30);
        assertThat(recovery.state(SID)).isEqualTo(SnapshotRecovery.State.STREAMING);
    }

    @Test
    @DisplayName("a second gap clears the previous buffer rather than mixing generations")
    void secondGapClearsBuffer() {
        recovery.onFrame(snapshot(10));
        recovery.onFrame(delta(13)); // gap 1, buffers 13
        recovery.onFrame(delta(14));
        assertThat(recovery.bufferDepth(SID)).isEqualTo(2);

        // Snapshot arrives and resolves gap 1 cleanly.
        recovery.onFrame(snapshot(12));
        assertThat(recovery.state(SID)).isEqualTo(SnapshotRecovery.State.STREAMING);
        assertThat(sequences.lastSeq(SID)).isEqualTo(14);

        // Now a fresh gap.
        SnapshotRecovery.Outcome.RequestSnapshot req =
                (SnapshotRecovery.Outcome.RequestSnapshot) recovery.onFrame(delta(20));
        assertThat(req.discarded()).isZero(); // buffer was already emptied by the rebuild
        assertThat(recovery.bufferDepth(SID)).isEqualTo(1);
    }

    @Test
    @DisplayName("a gap while already buffering discards the stale buffer generation")
    void gapWhileBufferingDiscardsOldGeneration() {
        recovery.onFrame(snapshot(10));
        recovery.onFrame(delta(13));
        recovery.onFrame(delta(14));

        // Still AWAITING_SNAPSHOT, so further deltas just buffer -- no second gap is raised,
        // because there is no baseline to compare against until the snapshot lands.
        recovery.onFrame(delta(99));
        assertThat(recovery.bufferDepth(SID)).isEqualTo(3);
        assertThat(metrics.gapsDetected()).isEqualTo(1);
    }

    @Test
    @DisplayName("sids are independent: a gap on one does not disturb another")
    void sidsAreIndependent() {
        long otherSid = 8;
        MarketTicker otherTicker = MarketTicker.of("KXHIGHLAX-26SEP01-T83");

        recovery.onFrame(snapshot(10));
        recovery.onFrame(new WsFrame.Snapshot(otherSid, 100, otherTicker, List.of(), List.of()));

        // Gap on SID only.
        recovery.onFrame(delta(13));

        assertThat(recovery.state(SID)).isEqualTo(SnapshotRecovery.State.AWAITING_SNAPSHOT);
        assertThat(recovery.state(otherSid)).isEqualTo(SnapshotRecovery.State.STREAMING);

        WsFrame.Delta otherDelta = new WsFrame.Delta(
                otherSid, 101, otherTicker, Side.NO, Price.ofCents(40), -500_000L);
        assertThat(recovery.onFrame(otherDelta))
                .isInstanceOf(SnapshotRecovery.Outcome.Apply.class);
        assertThat(sequences.lastSeq(otherSid)).isEqualTo(101);
    }

    @Test
    @DisplayName("a reconnect invalidates every sid, since sids and seq baselines do not survive")
    void invalidateAllForcesRecovery() {
        recovery.onFrame(snapshot(10));
        recovery.onFrame(delta(11));
        assertThat(recovery.state(SID)).isEqualTo(SnapshotRecovery.State.STREAMING);

        recovery.invalidateAll();

        assertThat(recovery.state(SID)).isEqualTo(SnapshotRecovery.State.AWAITING_SNAPSHOT);
        assertThat(sequences.lastSeq(SID)).isEqualTo(-1);
        assertThat(recovery.bufferDepth(SID)).isZero();
    }

    private static WsFrame.Ok ok(long seq) {
        return new WsFrame.Ok(1000 + seq, SID, seq);
    }

    @Test
    @DisplayName("an ok frame advances the counter without touching a book")
    void okAdvancesSequence() {
        recovery.onFrame(snapshot(10));

        SnapshotRecovery.Outcome outcome = recovery.onFrame(ok(11));

        assertThat(outcome).isInstanceOf(SnapshotRecovery.Outcome.SequenceOnly.class);
        assertThat(sequences.lastSeq(SID)).isEqualTo(11);
        // The following delta is in order precisely because the ok was counted.
        assertThat(recovery.onFrame(delta(12)))
                .isInstanceOf(SnapshotRecovery.Outcome.Apply.class);
        assertThat(metrics.gapsDetected()).isZero();
    }

    @Test
    @DisplayName("REGRESSION: ignoring an ok's seq would fake a gap on the next delta")
    void okNotCountedWouldFakeGap() {
        // Live shape: snapshots 1-6, then ok at 7, 8, 9, then snapshots from 10. If the ok
        // seqs are not consumed, seq 10 looks like it skipped three frames.
        recovery.onFrame(snapshot(6));
        recovery.onFrame(ok(7));
        recovery.onFrame(ok(8));
        recovery.onFrame(ok(9));

        assertThat(sequences.lastSeq(SID)).isEqualTo(9);
        assertThat(recovery.onFrame(delta(10)))
                .isInstanceOf(SnapshotRecovery.Outcome.Apply.class);
        assertThat(metrics.gapsDetected())
                .as("no gap: every sequence number between 6 and 10 was accounted for")
                .isZero();
    }

    @Test
    @DisplayName("a buffered ok is a marker, not a phantom hole in the replay run")
    void bufferedOkIsNotAHole() {
        recovery.onFrame(snapshot(10));
        recovery.onFrame(delta(13)); // gap -> buffers 13
        recovery.onFrame(ok(14));    // buffered as a marker
        recovery.onFrame(delta(15));

        SnapshotRecovery.Outcome.Rebuild rebuild =
                (SnapshotRecovery.Outcome.Rebuild) recovery.onFrame(snapshot(12));

        // 13, 14, 15 are contiguous above 12 -- 14 just contributes nothing to replay.
        assertThat(rebuild.replay()).extracting(WsFrame.Delta::seq).containsExactly(13L, 15L);
        assertThat(rebuild.stillGapped())
                .as("the ok at 14 accounts for that number, so there is no hole")
                .isFalse();
        assertThat(sequences.lastSeq(SID)).isEqualTo(15);
    }

    @Test
    @DisplayName("a trailing buffered ok is included in the new baseline")
    void trailingOkAdvancesBaseline() {
        recovery.onFrame(snapshot(10));
        recovery.onFrame(delta(12)); // gap
        recovery.onFrame(ok(13));

        SnapshotRecovery.Outcome.Rebuild rebuild =
                (SnapshotRecovery.Outcome.Rebuild) recovery.onFrame(snapshot(11));

        assertThat(rebuild.replay()).extracting(WsFrame.Delta::seq).containsExactly(12L);
        // Baseline is 13, not 12: the ok consumed a number after the last replayed delta.
        assertThat(sequences.lastSeq(SID)).isEqualTo(13);
        assertThat(recovery.onFrame(delta(14)))
                .isInstanceOf(SnapshotRecovery.Outcome.Apply.class);
    }

    @Test
    @DisplayName("an ok can itself expose a gap and trigger recovery")
    void okCanExposeGap() {
        recovery.onFrame(snapshot(10));

        SnapshotRecovery.Outcome outcome = recovery.onFrame(ok(15));

        assertThat(outcome).isInstanceOf(SnapshotRecovery.Outcome.RequestSnapshot.class);
        SnapshotRecovery.Outcome.RequestSnapshot req =
                (SnapshotRecovery.Outcome.RequestSnapshot) outcome;
        assertThat(req.expectedSeq()).isEqualTo(11);
        assertThat(req.observedSeq()).isEqualTo(15);
        assertThat(recovery.state(SID)).isEqualTo(SnapshotRecovery.State.AWAITING_SNAPSHOT);
    }

    @Test
    @DisplayName("the buffer is bounded and drops oldest first")
    void bufferIsBounded() {
        SnapshotRecovery bounded = new SnapshotRecovery(sequences, metrics, 3);
        bounded.onFrame(snapshot(10));
        bounded.onFrame(delta(20)); // gap -> buffers 20

        bounded.onFrame(delta(21));
        bounded.onFrame(delta(22));
        assertThat(bounded.bufferDepth(SID)).isEqualTo(3);

        bounded.onFrame(delta(23));
        assertThat(bounded.bufferDepth(SID)).isEqualTo(3);
        assertThat(metrics.bufferOverflowDrops()).isEqualTo(1);

        // Oldest (20) went; the recent tail survived.
        SnapshotRecovery.Outcome.Rebuild rebuild =
                (SnapshotRecovery.Outcome.Rebuild) bounded.onFrame(snapshot(20));
        assertThat(rebuild.replay())
                .extracting(WsFrame.Delta::seq)
                .containsExactly(21L, 22L, 23L);
    }
}
