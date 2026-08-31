package com.kalshiarb.recorder.archive;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.kalshiarb.model.EventTicker;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * Reads a gzipped JSON Lines archive back into {@link ArchivedFrame}s.
 *
 * <p>Handles the two things a real archive does that a freshly written one does not:
 *
 * <ul>
 *   <li><b>Concatenated gzip members.</b> The writer appends, so restarting the recorder on the
 *       same day produces several gzip members in one file. {@link GZIPInputStream} handles this
 *       natively as long as it is given a buffered stream.
 *   <li><b>A truncated final line.</b> A crash mid-write leaves a partial line. That line is
 *       skipped and counted rather than throwing, because losing the last frame of a recording
 *       must not make the preceding day unreadable — which is the whole reason the format is
 *       line-oriented.
 * </ul>
 */
public final class GzipJsonLinesFrameReader implements FrameReader {

    private static final JsonFactory FACTORY = new JsonFactory();

    private final Path path;
    private long malformedLines;
    private boolean truncatedStream;

    public GzipJsonLinesFrameReader(Path path) {
        this.path = path;
    }

    /** Lines that parsed as JSON but not as a frame envelope, or were cut mid-line. */
    public long malformedLines() {
        return malformedLines;
    }

    /**
     * True when the gzip stream ended mid-member and that was detectable.
     *
     * <p>Best-effort, not a guarantee, and the distinction is measured rather than assumed:
     * {@link GZIPInputStream#readTrailer} swallows an {@code IOException} from a malformed
     * <em>next-member header</em> and just reports end of stream. So a cut landing inside a
     * trailing member's 10-byte header is invisible here, while a cut landing in its deflate
     * data raises {@link EOFException} and sets this flag.
     *
     * <p>Either way every complete line before the cut is recovered, which is the property that
     * actually matters. Treat this as a diagnostic hint, not as an integrity check.
     */
    public boolean truncatedStream() {
        return truncatedStream;
    }

    /**
     * No-op: streams are opened and closed inside {@link #forEach}, so there is nothing held
     * open between calls. Declared because {@link FrameReader} is {@link java.io.Closeable} for
     * the benefit of implementations that do hold resources, such as a future Parquet reader.
     */
    @Override
    public void close() {
    }

    @Override
    public long forEach(Consumer<ArchivedFrame> consumer) throws IOException {
        long count = 0;
        malformedLines = 0;
        truncatedStream = false;

        // Deliberately not try-with-resources on the gzip stream. Closing a stream that ended
        // mid-member throws, and that exception would mask the frames we successfully read.
        // Recovering everything up to the cut is the entire point of a line-oriented archive.
        InputStream in = Files.newInputStream(path);
        GZIPInputStream gzip = null;
        try {
            gzip = new GZIPInputStream(in, 1 << 16);
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(gzip, StandardCharsets.UTF_8));
            while (true) {
                String line;
                try {
                    line = reader.readLine();
                } catch (EOFException | ZipException e) {
                    // The stream stops without a trailer: the writer is still open, or the
                    // process died. Everything already read stays valid.
                    truncatedStream = true;
                    break;
                }
                if (line == null) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                ArchivedFrame frame = parseLine(line);
                if (frame == null) {
                    malformedLines++;
                    continue;
                }
                consumer.accept(frame);
                count++;
            }
        } finally {
            closeQuietly(gzip);
            closeQuietly(in);
        }
        return count;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException | RuntimeException ignored) {
            // A truncated member throws on close; the frames are already delivered.
        }
    }

    private static ArchivedFrame parseLine(String line) {
        try (JsonParser p = FACTORY.createParser(line)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            long recvMs = -1;
            long recvNs = -1;
            String family = null;
            String frameJson = null;

            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                p.nextToken();
                switch (field) {
                    case "recvMs" -> recvMs = p.getValueAsLong();
                    case "recvNs" -> recvNs = p.getValueAsLong();
                    case "family" -> family = p.getValueAsString();
                    // The frame is stored raw; recover its exact text rather than re-encoding
                    // a parsed tree, which would silently normalise key order and spacing.
                    case "frame" -> frameJson = rawSubtree(p, line);
                    default -> p.skipChildren();
                }
            }
            if (family == null || frameJson == null || recvMs < 0) {
                return null;
            }
            return new ArchivedFrame(EventTicker.of(family), frameJson, recvMs, recvNs);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Extracts the exact source text of the value the parser is positioned on.
     *
     * <p>Uses byte offsets into the line rather than re-serialising, so a round trip through the
     * archive is textually identical to what the exchange sent.
     */
    private static String rawSubtree(JsonParser p, String line) throws IOException {
        int start = (int) p.currentTokenLocation().getCharOffset();
        p.skipChildren();
        // After skipChildren the parser sits on the value's final token; its end offset is the
        // first character after that token.
        int end = (int) p.currentLocation().getCharOffset();
        if (start < 0 || end > line.length() || end <= start) {
            return null;
        }
        return line.substring(start, end);
    }
}
