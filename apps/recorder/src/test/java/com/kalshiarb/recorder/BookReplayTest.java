package com.kalshiarb.recorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.kalshiarb.common.Clock;
import com.kalshiarb.model.EventTicker;
import com.kalshiarb.persistence.ArchivedFrame;
import com.kalshiarb.persistence.GzipJsonLinesFrameWriter;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the verification path itself by writing synthetic archives in the real wire format.
 *
 * <p>Necessary because the live archive currently contains no checkpoints, so replaying it never
 * reaches the comparison code at all. A verifier that has never compared anything is not a
 * verifier — the point of these tests is to prove it both accepts a correct delta stream and
 * <em>rejects</em> a corrupted one.
 */
class BookReplayTest {

    private static final EventTicker FAMILY = EventTicker.of("KXHIGHNY-26SEP01");
    private static final String TICKER = "KXHIGHNY-26SEP01-T83";
    private static final long T0 = 1_788_209_465_000L;

    private static String snapshot(long seq, String yesLevels, String noLevels) {
        return "{\"type\":\"orderbook_snapshot\",\"sid\":1,\"seq\":" + seq
                + ",\"msg\":{\"market_ticker\":\"" + TICKER + "\","
                + "\"yes_dollars_fp\":[" + yesLevels + "],"
                + "\"no_dollars_fp\":[" + noLevels + "]}}";
    }

    private static String delta(long seq, String side, String price, String size) {
        return "{\"type\":\"orderbook_delta\",\"sid\":1,\"seq\":" + seq
                + ",\"msg\":{\"market_ticker\":\"" + TICKER + "\",\"side\":\"" + side + "\","
                + "\"price_dollars\":\"" + price + "\",\"delta_fp\":\"" + size + "\","
                + "\"ts_ms\":" + (T0 + seq) + "}}";
    }

    /** Writes frames to a real gzipped archive and replays it. */
    private static BookReplay replayOf(Path dir, String... frames) throws IOException {
        try (GzipJsonLinesFrameWriter writer =
                new GzipJsonLinesFrameWriter(dir, "frames", Clock.fixed(T0))) {
            long i = 0;
            for (String f : frames) {
                writer.write(new ArchivedFrame(FAMILY, f, T0 + i, i));
                i++;
            }
        }
        BookReplay replay = new BookReplay();
        replay.replay(dir.resolve("frames-2026-08-31.jsonl.gz"));
        return replay;
    }

    @Test
    @DisplayName("a correct delta stream reproduces the next snapshot EXACTLY")
    void correctStreamMatchesSnapshot(@TempDir Path dir) throws IOException {
        BookReplay replay = replayOf(dir,
                // Baseline: YES 0.30 x10, NO 0.60 x20.
                snapshot(1, "[\"0.3000\",\"10.00\"]", "[\"0.6000\",\"20.00\"]"),
                // +5 YES at 0.30, new YES level at 0.31, NO 0.60 down by 8.
                delta(2, "yes", "0.3000", "5.00"),
                delta(3, "yes", "0.3100", "3.00"),
                delta(4, "no", "0.6000", "-8.00"),
                // Checkpoint the exchange would send: YES 0.30 x15 and 0.31 x3, NO 0.60 x12.
                snapshot(5, "[\"0.3000\",\"15.00\"],[\"0.3100\",\"3.00\"]",
                        "[\"0.6000\",\"12.00\"]"));

        assertThat(replay.comparisons()).isEqualTo(1);
        assertThat(replay.exactMatches()).isEqualTo(1);
        assertThat(replay.mismatches()).isEmpty();
        assertThat(replay.deltasApplied()).isEqualTo(3);
        assertThat(replay.isVerified()).isTrue();
    }

    @Test
    @DisplayName("a DROPPED delta is caught as a mismatch, not silently tolerated")
    void droppedDeltaIsCaught(@TempDir Path dir) throws IOException {
        // The exact failure this harness exists for: the archive is missing one update, so the
        // reconstruction is short by 5 contracts. Nothing else in the pipeline would notice.
        BookReplay replay = replayOf(dir,
                snapshot(1, "[\"0.3000\",\"10.00\"]", "[\"0.6000\",\"20.00\"]"),
                delta(2, "yes", "0.3100", "3.00"),
                // delta(3) raising YES 0.30 to 15.00 is absent.
                snapshot(4, "[\"0.3000\",\"15.00\"],[\"0.3100\",\"3.00\"]",
                        "[\"0.6000\",\"20.00\"]"));

        assertThat(replay.comparisons()).isEqualTo(1);
        assertThat(replay.exactMatches()).isZero();
        assertThat(replay.mismatches()).hasSize(1);
        assertThat(replay.mismatches().get(0))
                .contains(TICKER)
                .contains("size mismatch at $0.3000")
                .contains("reconstructed 10.00")
                .contains("snapshot 15.00");
        assertThat(replay.isVerified()).isFalse();
    }

    @Test
    @DisplayName("a DOUBLE-APPLIED delta is caught")
    void doubleAppliedDeltaIsCaught(@TempDir Path dir) throws IOException {
        BookReplay replay = replayOf(dir,
                snapshot(1, "[\"0.3000\",\"10.00\"]", "[\"0.6000\",\"20.00\"]"),
                delta(2, "yes", "0.3000", "5.00"),
                delta(3, "yes", "0.3000", "5.00"),
                // Exchange says 15; we have 20.
                snapshot(4, "[\"0.3000\",\"15.00\"]", "[\"0.6000\",\"20.00\"]"));

        assertThat(replay.mismatches()).hasSize(1);
        assertThat(replay.mismatches().get(0))
                .contains("reconstructed 20.00")
                .contains("snapshot 15.00");
        assertThat(replay.isVerified()).isFalse();
    }

    @Test
    @DisplayName("a level the exchange no longer reports is caught as an extra level")
    void extraLevelIsCaught(@TempDir Path dir) throws IOException {
        BookReplay replay = replayOf(dir,
                snapshot(1, "[\"0.3000\",\"10.00\"]", "[\"0.6000\",\"20.00\"]"),
                delta(2, "yes", "0.3100", "3.00"),
                // The 0.31 level is gone in the exchange's view but we still hold it.
                snapshot(3, "[\"0.3000\",\"10.00\"]", "[\"0.6000\",\"20.00\"]"));

        assertThat(replay.mismatches()).hasSize(1);
        assertThat(replay.mismatches().get(0)).contains("extra level $0.3100");
    }

    @Test
    @DisplayName("a level removed to zero is dropped and matches a snapshot that omits it")
    void levelRemovedToZeroMatches(@TempDir Path dir) throws IOException {
        BookReplay replay = replayOf(dir,
                snapshot(1, "[\"0.3000\",\"10.00\"],[\"0.3100\",\"4.00\"]",
                        "[\"0.6000\",\"20.00\"]"),
                delta(2, "yes", "0.3100", "-4.00"),
                snapshot(3, "[\"0.3000\",\"10.00\"]", "[\"0.6000\",\"20.00\"]"));

        assertThat(replay.comparisons()).isEqualTo(1);
        assertThat(replay.exactMatches()).isEqualTo(1);
        assertThat(replay.isVerified()).isTrue();
    }

    @Test
    @DisplayName("an archive with no checkpoints reports UNVERIFIED, never OK")
    void noCheckpointsIsNotAPass(@TempDir Path dir) throws IOException {
        BookReplay replay = replayOf(dir,
                snapshot(1, "[\"0.3000\",\"10.00\"]", "[\"0.6000\",\"20.00\"]"),
                delta(2, "yes", "0.3000", "5.00"),
                delta(3, "yes", "0.3100", "1.00"));

        // No failures, but nothing was proven either -- and those must not read the same.
        assertThat(replay.noFailures()).isTrue();
        assertThat(replay.comparisons()).isZero();
        assertThat(replay.isVerified())
                .as("zero comparisons is not a pass")
                .isFalse();
    }

    @Test
    @DisplayName("fractional deltas reconstruct exactly, as real 0.33-contract frames require")
    void fractionalDeltasReconstructExactly(@TempDir Path dir) throws IOException {
        BookReplay replay = replayOf(dir,
                snapshot(1, "[\"0.3900\",\"1.00\"]", "[\"0.6000\",\"1.00\"]"),
                delta(2, "yes", "0.3900", "0.33"),
                delta(3, "yes", "0.3900", "0.33"),
                delta(4, "yes", "0.3900", "0.34"),
                snapshot(5, "[\"0.3900\",\"2.00\"]", "[\"0.6000\",\"1.00\"]"));

        assertThat(replay.exactMatches()).isEqualTo(1);
        assertThat(replay.isVerified()).isTrue();
    }
}
