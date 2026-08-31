package com.kalshiarb.model;

/**
 * A Kalshi contract price in micro-dollars, where $1.00 == 1_000_000.
 *
 * <p>Constrained to {@code [0, $1.00]}: a Kalshi event contract settles at either $0 or $1,
 * so a price outside that band is a parse or arithmetic bug, not a market condition.
 *
 * @param micros price in micro-dollars
 */
public record Price(long micros) implements Comparable<Price> {

    /** $0.00. */
    public static final Price ZERO = new Price(0);

    /** $1.00 — the settlement value of a winning contract and the bracket-sum target. */
    public static final Price ONE_DOLLAR = new Price(FixedPoint.ONE);

    public Price {
        if (micros < 0 || micros > FixedPoint.ONE) {
            throw new IllegalArgumentException(
                    "price out of [0, $1.00]: " + FixedPoint.format(micros, 4) + " ("
                            + micros + " micros)");
        }
    }

    /**
     * Parses a Kalshi {@code _dollars} field, e.g. {@code "0.0800"}, {@code "0.960"},
     * {@code "0.9"}.
     */
    public static Price fromFixedPointString(String dollars) {
        return new Price(FixedPoint.parseMicros(dollars));
    }

    /** Convenience for tests and the fee model: {@code ofCents(8) == $0.08}. */
    public static Price ofCents(long cents) {
        return new Price(cents * 10_000L);
    }

    /** Renders in Kalshi's wire format, e.g. {@code "0.0800"}. */
    public String toDollarString() {
        return FixedPoint.format(micros, 4);
    }

    /**
     * Returns {@code $1.00 - this}.
     *
     * <p>This is the implied-ask conversion. Kalshi publishes bids only, on both sides, so a
     * NO bid at $0.45 <em>is</em> a YES ask at $0.55. Every ask in this system is produced by
     * complementing a bid on the opposite side.
     */
    public Price complement() {
        return new Price(FixedPoint.ONE - micros);
    }

    public Price plus(Price other) {
        return new Price(micros + other.micros);
    }

    public Price minus(Price other) {
        return new Price(micros - other.micros);
    }

    public boolean isGreaterThan(Price other) {
        return micros > other.micros;
    }

    public boolean isLessThan(Price other) {
        return micros < other.micros;
    }

    @Override
    public int compareTo(Price other) {
        return Long.compare(micros, other.micros);
    }

    @Override
    public String toString() {
        return "$" + toDollarString();
    }
}
