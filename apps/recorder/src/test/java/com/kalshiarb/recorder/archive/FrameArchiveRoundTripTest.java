package com.kalshiarb.recorder.archive;

import static org.assertj.core.api.Assertions.assertThat;

import com.kalshiarb.common.Clock;
import com.kalshiarb.model.EventTicker;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A writer whose output has never been read back is an unverified writer, and the data it
 * archives cannot be regenerated. These tests exercise the round trip, including the failure
 * shapes a long-running unattended recorder actually produces: restarts mid-day, day rollover,
 * and a truncated tail from a hard kill.
 */
class FrameArchiveRoundTripTest {

    private static final EventTicker NY = EventTicker.of("KXHIGHNY-26SEP01");
    private static final EventTicker LAX = EventTicker.of("KXHIGHLAX-26SEP01");

    /** 2026-08-31T20:51:05Z */
    private static final long T0 = 1_788_209_465_000L;

    /** A real captured delta, complete with the fields we must not mangle. */
    private static final String DELTA_JSON = """
            {"type":"orderbook_delta","sid":1,"seq":28,"msg":{\
            "market_ticker":"KXHIGHLAX-26SEP01-B76.5",\
            "market_id":"78a9ab48-fcf5-4b06-afdd-f6bfc4c7e6c8",\
            "price_dollars":"0.3900","delta_fp":"0.33","side":"yes",\
            "ts":"2026-08-31T20:51:05.261155Z","ts_ms":1788209465261}}""";

    private static List<ArchivedFrame> readAll(Path file) throws IOException {
        List<ArchivedFrame> out = new ArrayList<>();
        try (GzipJsonLinesFrameReader reader = new GzipJsonLinesFrameReader(file)) {
            reader.forEach(out::add);
        }
        return out;
    }

    @Test
    @DisplayName("a frame round-trips with its bytes, family and both timestamps intact")
    void roundTripsExactly(@TempDir Path dir) throws IOException {
        Path file;
        try (GzipJsonLinesFrameWriter writer =
                new GzipJsonLinesFrameWriter(dir, "frames", Clock.fixed(T0))) {
            writer.write(new ArchivedFrame(LAX, DELTA_JSON, T0, 123_456_789L));
            file = writer.currentPath();
        }

        List<ArchivedFrame> read = readAll(file);

        assertThat(read).hasSize(1);
        ArchivedFrame f = read.get(0);
        assertThat(f.family()).isEqualTo(LAX);
        assertThat(f.receivedAtMillis()).isEqualTo(T0);
        assertThat(f.receivedAtNanos()).isEqualTo(123_456_789L);
        // Byte-for-byte: the archive must outlive the parser, so nothing is re-serialised.
        assertThat(f.json()).isEqualTo(DELTA_JSON);
    }

    @Test
    @DisplayName("the file is gzipped JSON Lines: one line per frame, .jsonl.gz name")
    void formatIsOneLinePerFrame(@TempDir Path dir) throws IOException {
        Path file;
        try (GzipJsonLinesFrameWriter writer =
                new GzipJsonLinesFrameWriter(dir, "frames", Clock.fixed(T0))) {
            for (int i = 0; i < 5; i++) {
                writer.write(new ArchivedFrame(NY, DELTA_JSON, T0 + i, i));
            }
            file = writer.currentPath();
        }

        assertThat(file.getFileName().toString()).isEqualTo("frames-2026-08-31.jsonl.gz");

        // Decompressed content is exactly 5 newline-terminated lines.
        List<String> lines = new ArrayList<>();
        try (var gz = new java.util.zip.GZIPInputStream(Files.newInputStream(file));
                var r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(gz, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                lines.add(line);
            }
        }
        assertThat(lines).hasSize(5);
        assertThat(lines.get(0))
                .startsWith("{\"v\":1,\"recvMs\":" + T0)
                .contains("\"family\":\"KXHIGHNY-26SEP01\"")
                .contains("\"frame\":{\"type\":\"orderbook_delta\"");
    }

    @Test
    @DisplayName("frames from several families multiplex into one file, each keeping its origin")
    void multiplexesFamilies(@TempDir Path dir) throws IOException {
        Path file;
        try (GzipJsonLinesFrameWriter writer =
                new GzipJsonLinesFrameWriter(dir, "frames", Clock.fixed(T0))) {
            writer.write(new ArchivedFrame(NY, DELTA_JSON, T0, 1));
            writer.write(new ArchivedFrame(LAX, DELTA_JSON, T0 + 1, 2));
            writer.write(new ArchivedFrame(NY, DELTA_JSON, T0 + 2, 3));
            file = writer.currentPath();
        }

        // One file, but replay can still partition back into per-shard sequence streams.
        assertThat(readAll(file)).extracting(ArchivedFrame::family)
                .containsExactly(NY, LAX, NY);
    }

    @Test
    @DisplayName("rotation is by UTC day of the frame's own timestamp, not the wall clock")
    void rotatesByFrameDay(@TempDir Path dir) throws IOException {
        long justBeforeMidnight = 1_788_220_799_000L; // 2026-08-31T23:59:59Z
        long justAfterMidnight = 1_788_220_800_000L;  // 2026-09-01T00:00:00Z

        // Clock is frozen in August on purpose: if rotation used the clock instead of the frame,
        // the September frame would land in the August file.
        try (GzipJsonLinesFrameWriter writer =
                new GzipJsonLinesFrameWriter(dir, "frames", Clock.fixed(T0))) {
            writer.write(new ArchivedFrame(NY, DELTA_JSON, justBeforeMidnight, 1));
            writer.write(new ArchivedFrame(NY, DELTA_JSON, justAfterMidnight, 2));
        }

        Path august = dir.resolve("frames-2026-08-31.jsonl.gz");
        Path september = dir.resolve("frames-2026-09-01.jsonl.gz");
        assertThat(august).exists();
        assertThat(september).exists();
        assertThat(readAll(august)).hasSize(1);
        assertThat(readAll(september)).hasSize(1);
        assertThat(readAll(september).get(0).receivedAtMillis()).isEqualTo(justAfterMidnight);
    }

    @Test
    @DisplayName("a restart on the same day appends; concatenated gzip members read as one file")
    void restartAppends(@TempDir Path dir) throws IOException {
        try (GzipJsonLinesFrameWriter first =
                new GzipJsonLinesFrameWriter(dir, "frames", Clock.fixed(T0))) {
            first.write(new ArchivedFrame(NY, DELTA_JSON, T0, 1));
            first.write(new ArchivedFrame(NY, DELTA_JSON, T0 + 1, 2));
        }
        // Second process, same day. Truncating here would destroy the morning's recording.
        try (GzipJsonLinesFrameWriter second =
                new GzipJsonLinesFrameWriter(dir, "frames", Clock.fixed(T0))) {
            second.write(new ArchivedFrame(LAX, DELTA_JSON, T0 + 2, 3));
        }

        Path file = dir.resolve("frames-2026-08-31.jsonl.gz");
        assertThat(readAll(file)).hasSize(3);
        assertThat(readAll(file)).extracting(ArchivedFrame::receivedAtMillis)
                .containsExactly(T0, T0 + 1, T0 + 2);
    }

    @Test
    @DisplayName("a truncated tail costs only the final line, not the whole day")
    void truncatedTailIsSurvivable(@TempDir Path dir) throws IOException {
        // Simulates kill -9 mid-write: a complete gzip member, then a partial line in a second
        // member. This is the property that justifies JSON Lines over a columnar format.
        Path file = dir.resolve("frames-2026-08-31.jsonl.gz");
        try (GzipJsonLinesFrameWriter writer =
                new GzipJsonLinesFrameWriter(dir, "frames", Clock.fixed(T0))) {
            writer.write(new ArchivedFrame(NY, DELTA_JSON, T0, 1));
            writer.write(new ArchivedFrame(NY, DELTA_JSON, T0 + 1, 2));
        }
        try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.APPEND);
                GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write("{\"v\":1,\"recvMs\":1788209465002,\"fam".getBytes(StandardCharsets.UTF_8));
        }

        List<ArchivedFrame> read;
        try (GzipJsonLinesFrameReader reader = new GzipJsonLinesFrameReader(file)) {
            read = new ArrayList<>();
            long n = reader.forEach(read::add);

            assertThat(n).isEqualTo(2);
            assertThat(reader.malformedLines()).isEqualTo(1);
        }
        assertThat(read).extracting(ArchivedFrame::receivedAtMillis)
                .containsExactly(T0, T0 + 1);
    }

    @Test
    @DisplayName("gzip actually compresses, and the counters report raw bytes")
    void compresses(@TempDir Path dir) throws IOException {
        Path file;
        long rawBytes;
        try (GzipJsonLinesFrameWriter writer =
                new GzipJsonLinesFrameWriter(dir, "frames", Clock.fixed(T0))) {
            for (int i = 0; i < 500; i++) {
                writer.write(new ArchivedFrame(NY, DELTA_JSON, T0 + i, i));
            }
            file = writer.currentPath();
            assertThat(writer.framesWritten()).isEqualTo(500);
            rawBytes = writer.bytesWritten();
        }

        assertThat(rawBytes).isEqualTo(500L * DELTA_JSON.length());
        // Highly repetitive frames; even with sync-flush overhead this should shrink a lot.
        assertThat(Files.size(file)).isLessThan(rawBytes / 4);
        assertThat(readAll(file)).hasSize(500);
    }

    @Test
    @DisplayName("a flush leaves a FULLY VALID file, not one needing a tolerant reader")
    void flushMakesDataReadable(@TempDir Path dir) throws IOException {
        // The unattended case: the process is still running and must not be holding hours of
        // frames in a buffer. Deflater.SYNC_FLUSH is not sufficient here -- it puts the bytes on
        // disk but leaves the member without a trailer, and GZIPInputStream then cannot extract
        // the line. Hence: finish the member on flush.
        GzipJsonLinesFrameWriter writer =
                new GzipJsonLinesFrameWriter(dir, "frames", Clock.fixed(T0));
        try {
            writer.write(new ArchivedFrame(NY, DELTA_JSON, T0, 1));
            writer.flush();

            Path file = writer.currentPath();
            try (GzipJsonLinesFrameReader reader = new GzipJsonLinesFrameReader(file)) {
                List<ArchivedFrame> read = new ArrayList<>();
                assertThat(reader.forEach(read::add)).isEqualTo(1);
                assertThat(reader.truncatedStream())
                        .as("a flushed archive must be complete, not merely salvageable")
                        .isFalse();
                assertThat(read.get(0).json()).isEqualTo(DELTA_JSON);
            }

            // Writing continues into a new member, and the whole file stays readable.
            writer.write(new ArchivedFrame(LAX, DELTA_JSON, T0 + 1, 2));
            writer.flush();
            assertThat(readAll(file)).hasSize(2);
        } finally {
            writer.close();
        }
    }

    @Test
    @DisplayName("kill -9 mid-member: everything before the cut is still recovered")
    void truncatedGzipMemberIsSurvivable(@TempDir Path dir) throws IOException {
        Path file;
        long safeSize;
        try (GzipJsonLinesFrameWriter writer =
                new GzipJsonLinesFrameWriter(dir, "frames", Clock.fixed(T0))) {
            for (int i = 0; i < 10; i++) {
                writer.write(new ArchivedFrame(NY, DELTA_JSON, T0 + i, i));
            }
            writer.flush(); // 10 frames now sit in a finished, complete member
            file = writer.currentPath();
            safeSize = Files.size(file);

            // Further frames land in a new member.
            for (int i = 0; i < 20; i++) {
                writer.write(new ArchivedFrame(LAX, DELTA_JSON, T0 + 100 + i, i));
            }
        }
        assertThat(Files.size(file)).isGreaterThan(safeSize);

        byte[] bytes = Files.readAllBytes(file);

        // Two distinct power-loss shapes, because GZIPInputStream treats them differently.
        //   +8  : the next member's 10-byte header is itself incomplete. GZIPInputStream's
        //         readTrailer swallows the malformed header and simply reports end of stream,
        //         so the cut is NOT detectable.
        //   +40 : the header parses, then the deflate data runs out -> EOFException, detectable.
        // The guarantee that matters holds in both: every flushed frame comes back.
        for (int extra : new int[] {8, 40}) {
            byte[] cut = new byte[(int) safeSize + extra];
            System.arraycopy(bytes, 0, cut, 0, Math.min(bytes.length, cut.length));
            Files.write(file, cut);

            try (GzipJsonLinesFrameReader reader = new GzipJsonLinesFrameReader(file)) {
                List<ArchivedFrame> read = new ArrayList<>();
                long n = reader.forEach(read::add);

                // The 10 flushed frames survive a corrupted tail -- the property that justifies
                // a line-oriented, member-per-flush archive over a columnar one.
                assertThat(n)
                        .as("cut at +%d must still recover every flushed frame", extra)
                        .isGreaterThanOrEqualTo(10);
                assertThat(read.subList(0, 10))
                        .extracting(ArchivedFrame::receivedAtMillis)
                        .containsExactly(T0, T0 + 1, T0 + 2, T0 + 3, T0 + 4,
                                T0 + 5, T0 + 6, T0 + 7, T0 + 8, T0 + 9);
                assertThat(read).allSatisfy(f ->
                        assertThat(f.json()).isEqualTo(DELTA_JSON));
            }
        }
    }

    @Test
    @DisplayName("a frame the codec cannot decode is still archived verbatim")
    void archivesUndecodableFrames(@TempDir Path dir) throws IOException {
        // The yes_dollars_fp lesson: the archive must not depend on our understanding of the
        // protocol, or a codec bug silently costs a day of data.
        String unknown = "{\"type\":\"some_future_channel\",\"payload\":[1,2,{\"deep\":null}]}";
        Path file;
        try (GzipJsonLinesFrameWriter writer =
                new GzipJsonLinesFrameWriter(dir, "frames", Clock.fixed(T0))) {
            writer.write(new ArchivedFrame(NY, unknown, T0, 1));
            file = writer.currentPath();
        }

        assertThat(readAll(file).get(0).json()).isEqualTo(unknown);
    }

    @Test
    @DisplayName("close is idempotent, since both a shutdown hook and a finally block call it")
    void closeIsIdempotent(@TempDir Path dir) throws IOException {
        GzipJsonLinesFrameWriter writer =
                new GzipJsonLinesFrameWriter(dir, "frames", Clock.fixed(T0));
        writer.write(new ArchivedFrame(NY, DELTA_JSON, T0, 1));
        writer.close();
        writer.close();

        assertThat(readAll(dir.resolve("frames-2026-08-31.jsonl.gz"))).hasSize(1);
    }
}
