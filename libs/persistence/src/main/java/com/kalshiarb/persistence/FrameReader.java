package com.kalshiarb.persistence;

import java.io.Closeable;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * Source of archived frames, the read half of the format seam.
 *
 * <p>Exists now, before anything needs it, because a writer without a reader is an unverifiable
 * writer: the only real proof that an archive is usable is reading it back. It is also what the
 * replay engine and the book-replay verification in task 6 will consume.
 *
 * <p>Deliberately push-based rather than an {@code Iterator}. Frames are read in file order and
 * handed straight to a consumer, so a whole day's recording never has to be held in memory at
 * once.
 */
public interface FrameReader extends Closeable {

    /**
     * Reads every frame in order, passing each to {@code consumer}.
     *
     * @return the number of frames read
     */
    long forEach(Consumer<ArchivedFrame> consumer) throws IOException;
}
