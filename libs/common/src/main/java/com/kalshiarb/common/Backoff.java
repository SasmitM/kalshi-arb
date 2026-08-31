package com.kalshiarb.common;

import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with jitter, for WebSocket reconnection.
 *
 * <p>Uses <b>equal jitter</b>: the delay is half the current cap plus a random draw over the
 * other half. Full jitter (a uniform draw over the whole interval) can return a near-zero delay,
 * which is the wrong behaviour after an authentication rejection or a server-side throttle —
 * precisely the cases where reconnecting immediately is worst. Equal jitter keeps a guaranteed
 * floor while still decorrelating retries.
 *
 * <p>The random source is injected for the same reason {@link Clock} is: a test asserting the
 * delay schedule must be deterministic.
 *
 * <p>Not thread-safe. One instance per connection, mutated only by that connection's reconnect
 * loop.
 */
public final class Backoff {

    private final long baseMillis;
    private final long maxMillis;
    private final double multiplier;
    private final RandomGenerator random;

    /**
     * Exponent ceiling. {@code multiplier^attempts} is computed in {@code double}, so without a
     * bound a long-lived outage would push the cap to infinity and then to a nonsense
     * {@code long}. Capping the exponent keeps the arithmetic finite; {@link #maxMillis} then
     * does the real clamping.
     */
    private static final int MAX_EXPONENT = 32;

    private int attempts;

    public Backoff(long baseMillis, long maxMillis, double multiplier, RandomGenerator random) {
        if (baseMillis <= 0) {
            throw new IllegalArgumentException("baseMillis must be positive: " + baseMillis);
        }
        if (maxMillis < baseMillis) {
            throw new IllegalArgumentException(
                    "maxMillis " + maxMillis + " is below baseMillis " + baseMillis);
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1: " + multiplier);
        }
        this.baseMillis = baseMillis;
        this.maxMillis = maxMillis;
        this.multiplier = multiplier;
        this.random = Objects.requireNonNull(random, "random");
    }

    /** 500ms base, doubling, capped at 30s — fast enough to recover, slow enough to be polite. */
    public static Backoff forWebSocket(RandomGenerator random) {
        return new Backoff(500L, 30_000L, 2.0, random);
    }

    /**
     * The delay to wait before the next attempt, then advances the schedule.
     *
     * <p>Returns a value in {@code [cap/2, cap]} where {@code cap = min(max, base * mult^n)}.
     */
    public long nextDelayMillis() {
        long cap = capForAttempt(attempts);
        if (attempts < Integer.MAX_VALUE) {
            attempts++;
        }
        long half = cap / 2;
        return half + (long) (random.nextDouble() * (cap - half));
    }

    /** The cap that {@link #nextDelayMillis()} would use next, without advancing. */
    public long currentCapMillis() {
        return capForAttempt(attempts);
    }

    private long capForAttempt(int attempt) {
        double scaled = baseMillis * Math.pow(multiplier, Math.min(attempt, MAX_EXPONENT));
        return scaled >= maxMillis ? maxMillis : (long) scaled;
    }

    /** Call after a connection is healthy again, so the next outage starts from the base delay. */
    public void reset() {
        attempts = 0;
    }

    public int attempts() {
        return attempts;
    }
}
