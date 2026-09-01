package com.kalshiarb.persistence;

import java.io.Closeable;
import java.io.IOException;

/**
 * Sink for archived frames.
 *
 * <p>The archive sits behind this interface so gzipped JSON Lines can be swapped for Parquet
 * without touching a single caller — required by CLAUDE.md section 3, and the reason Parquet
 * can be deferred without painting the project into a corner. Nothing above this interface may
 * know what the on-disk format is.
 *
 * <p>Implementations must be safe to call from multiple threads: with one WebSocket connection
 * per event family, several pump threads write concurrently.
 */
public interface FrameWriter extends Closeable {

    /**
     * Appends one frame.
     *
     * <p>May buffer. Durability is only guaranteed after {@link #flush()} or {@link #close()}.
     */
    void write(ArchivedFrame frame) throws IOException;

    /** Pushes buffered data far enough that a reader can recover it. */
    void flush() throws IOException;

    /** Frames written since construction. */
    long framesWritten();

    /** Uncompressed bytes of frame JSON written, for a rough compression figure. */
    long bytesWritten();
}
