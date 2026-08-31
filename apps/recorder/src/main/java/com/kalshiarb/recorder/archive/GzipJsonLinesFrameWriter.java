package com.kalshiarb.recorder.archive;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.kalshiarb.common.Clock;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.zip.GZIPOutputStream;

/**
 * Writes frames as gzipped JSON Lines, one file per UTC day.
 *
 * <p>Each line is a self-describing envelope wrapping the untouched frame:
 *
 * <pre>{@code
 * {"v":1,"recvMs":1788209465282,"recvNs":90271,"family":"KXHIGHNY-26SEP01","frame":{...}}
 * }</pre>
 *
 * <p>{@code frame} is written with {@code writeRawValue}, so the original text is copied through
 * without being parsed and re-emitted. That keeps the archive byte-faithful and, more usefully,
 * means a frame this codebase cannot yet decode is still archived intact.
 *
 * <h2>Why JSON Lines and gzip</h2>
 * One line per frame means a truncated file — a kill -9, a full disk, a laptop lid — costs at
 * most the final line rather than the whole archive. That property is worth more here than
 * columnar efficiency: the recording cannot be regenerated. Parquet is deferred and reachable
 * through {@link FrameWriter} whenever it earns its place.
 *
 * <h2>Rotation</h2>
 * By UTC day, chosen from each frame's own receive time rather than from the clock at write
 * time, so a frame never lands in a file whose name disagrees with its timestamp. UTC rather
 * than local time avoids two malformed days a year under DST.
 *
 * <h2>Durability</h2>
 * A flush <b>finishes the current gzip member</b> and the next write appends a new one, every
 * {@value #FLUSH_EVERY_FRAMES} frames or {@value #FLUSH_INTERVAL_MILLIS} ms. Concatenated gzip
 * members are themselves a valid gzip stream, so after any flush the file on disk is
 * <b>completely valid</b> — not merely readable by a tolerant reader.
 *
 * <p>{@code Deflater.SYNC_FLUSH} was the obvious choice and is not enough. Measured: after a
 * sync flush the bytes are on disk (92 of them for one frame) but {@link GZIPInputStream} still
 * cannot extract the line, because the member has no trailer; the same file read cleanly at 102
 * bytes once closed. An unattended recorder whose last flush is unreadable has silently lost
 * data, which is the single failure this program exists to prevent. Finishing the member costs
 * ~20 bytes of header and trailer per flush — negligible against 200 frames — and one file
 * reopen every few seconds.
 *
 * <h2>Concurrency</h2>
 * {@link #write} is synchronised. One socket per event family means several pump threads write
 * here, and neither {@link GZIPOutputStream} nor the generator is thread-safe. A lock rather
 * than a queue-and-writer-thread because the measured frame rate is ~36/s against a local SSD,
 * where the critical section is microseconds; a dedicated thread would add a second bounded
 * queue and a second drop policy for no benefit at this rate. If the rate ever rises far enough
 * that pump threads contend, the fix is that queue — the interface does not change.
 */
public final class GzipJsonLinesFrameWriter implements FrameWriter {

    /** Flush at least this often, in frames. */
    static final int FLUSH_EVERY_FRAMES = 200;

    /** Flush at least this often, in milliseconds — matters when the market is quiet. */
    static final long FLUSH_INTERVAL_MILLIS = 5_000;

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private static final JsonFactory FACTORY = new JsonFactory();

    private final Path directory;
    private final String prefix;
    private final Clock clock;

    private String currentDay;
    private Path currentPath;
    private Writer writer;
    private JsonGenerator generator;

    private long framesWritten;
    private long bytesWritten;
    private int framesSinceFlush;
    private long lastFlushMillis;

    public GzipJsonLinesFrameWriter(Path directory, String prefix, Clock clock) {
        this.directory = directory;
        this.prefix = prefix;
        this.clock = clock;
        this.lastFlushMillis = clock.currentTimeMillis();
    }

    /** The file currently being appended to, or null before the first write. */
    public synchronized Path currentPath() {
        return currentPath;
    }

    @Override
    public synchronized void write(ArchivedFrame frame) throws IOException {
        String day = DAY.format(Instant.ofEpochMilli(frame.receivedAtMillis()));
        // Reopens after a flush closed the previous member, and rolls the file at UTC midnight.
        if (generator == null || !day.equals(currentDay)) {
            openMember(day);
        }

        generator.writeStartObject();
        generator.writeNumberField("v", ArchivedFrame.SCHEMA_VERSION);
        generator.writeNumberField("recvMs", frame.receivedAtMillis());
        generator.writeNumberField("recvNs", frame.receivedAtNanos());
        generator.writeStringField("family", frame.family().value());
        generator.writeFieldName("frame");
        // Raw: never re-serialise the exchange's own bytes.
        generator.writeRawValue(frame.json());
        generator.writeEndObject();
        generator.writeRaw('\n');

        framesWritten++;
        bytesWritten += frame.json().length();
        framesSinceFlush++;

        long now = clock.currentTimeMillis();
        if (framesSinceFlush >= FLUSH_EVERY_FRAMES
                || now - lastFlushMillis >= FLUSH_INTERVAL_MILLIS) {
            flushLocked(now);
        }
    }

    /** Opens a fresh gzip member, appending to the file for {@code day}. */
    private void openMember(String day) throws IOException {
        closeCurrent();
        Files.createDirectories(directory);
        currentPath = directory.resolve(prefix + "-" + day + ".jsonl.gz");
        currentDay = day;

        // APPEND, always: both a flush and a same-day restart must extend the file rather than
        // truncate it. Losing a morning's recording to a restart would be unrecoverable.
        OutputStream out = Files.newOutputStream(currentPath,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        GZIPOutputStream gzip = new GZIPOutputStream(new BufferedOutputStream(out, 1 << 16));
        writer = new OutputStreamWriter(gzip, StandardCharsets.UTF_8);
        generator = FACTORY.createGenerator(writer);
        framesSinceFlush = 0;
        lastFlushMillis = clock.currentTimeMillis();
    }

    @Override
    public synchronized void flush() throws IOException {
        flushLocked(clock.currentTimeMillis());
    }

    /**
     * Finishes the current member so what is on disk is a complete gzip stream.
     *
     * <p>The next {@link #write} transparently opens a new member.
     */
    private void flushLocked(long now) throws IOException {
        closeCurrent();
        framesSinceFlush = 0;
        lastFlushMillis = now;
    }

    private void closeCurrent() throws IOException {
        if (generator != null) {
            generator.flush();
            generator.close();
            generator = null;
        }
        if (writer != null) {
            // Closes the gzip stream, which writes the member trailer and the file bytes.
            writer.close();
            writer = null;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        closeCurrent();
        currentDay = null;
    }

    @Override
    public synchronized long framesWritten() {
        return framesWritten;
    }

    @Override
    public synchronized long bytesWritten() {
        return bytesWritten;
    }
}
