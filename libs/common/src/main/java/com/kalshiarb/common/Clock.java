package com.kalshiarb.common;

import java.time.Instant;

/**
 * The only source of time below {@code apps/}.
 *
 * <p>Nothing in {@code libs/} may call {@link System#currentTimeMillis()} or
 * {@link System#nanoTime()} directly. This is not stylistic: the whole point of the project is
 * to replay recorded frames through byte-identical detector code against a simulated clock and
 * measure what survives at 10ms / 50ms / 200ms / 1s of latency. A single hidden call to the
 * real clock in a library makes that measurement wrong, and wrong in a way that looks fine.
 *
 * <p>Two separate readings, because they answer different questions:
 * <ul>
 *   <li>{@link #currentTimeMillis()} — wall time, correlatable with the exchange. Required for
 *       auth signatures, which Kalshi validates against its own clock.
 *   <li>{@link #nanoTime()} — monotonic, for measuring durations. Never for signatures: it has
 *       no defined epoch and jumps between processes.
 * </ul>
 *
 * <p>Deliberately not {@link java.time.Clock}: that type has no monotonic reading, and it
 * encourages allocating an {@code Instant} per tick on a path that runs per frame.
 */
public interface Clock {

    /** Wall-clock milliseconds since the Unix epoch. */
    long currentTimeMillis();

    /** Monotonic nanoseconds. Meaningful only as a difference against another reading. */
    long nanoTime();

    /** Convenience for logging and DTOs. Allocates; keep it off the hot path. */
    default Instant instant() {
        return Instant.ofEpochMilli(currentTimeMillis());
    }

    /** The real clock. Only {@code apps/} should call this. */
    static Clock system() {
        return SystemClock.INSTANCE;
    }

    /**
     * A clock frozen at {@code millis}, for tests that need a deterministic auth timestamp.
     * Its {@link #nanoTime()} advances not at all, so durations measured against it are zero.
     */
    static Clock fixed(long millis) {
        return new Clock() {
            @Override
            public long currentTimeMillis() {
                return millis;
            }

            @Override
            public long nanoTime() {
                return 0L;
            }
        };
    }
}
