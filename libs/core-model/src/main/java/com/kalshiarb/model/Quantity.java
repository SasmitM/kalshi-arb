package com.kalshiarb.model;

/**
 * A contract count in micro-contracts, where 1 contract == 1_000_000.
 *
 * <p>Kalshi counts are fractional on the wire — real order-book levels include
 * {@code "102.12"} and {@code "19.41"} — so an integer contract count would be wrong.
 *
 * <p>A {@code Quantity} is always non-negative: it models the size resting at a price level,
 * not a change to it. Order-book deltas are signed and are carried as raw
 * {@code long} micro-contracts by the codec; they are applied through
 * {@link #plusSigned(long)}, which throws rather than clamping when a level would go negative.
 *
 * @param microContracts contract count in micro-contracts
 */
public record Quantity(long microContracts) implements Comparable<Quantity> {

    public static final Quantity ZERO = new Quantity(0);

    public Quantity {
        if (microContracts < 0) {
            throw new IllegalArgumentException(
                    "quantity is negative: " + FixedPoint.format(microContracts, 2));
        }
    }

    /** Parses a Kalshi {@code _fp} count field, e.g. {@code "300.00"}, {@code "19.41"}. */
    public static Quantity fromFixedPointString(String count) {
        return new Quantity(FixedPoint.parseMicros(count));
    }

    public static Quantity ofContracts(long contracts) {
        return new Quantity(contracts * FixedPoint.ONE);
    }

    /**
     * Applies a signed delta.
     *
     * @throws IllegalArgumentException if the result would be negative. Clamping to zero here
     *                                 would hide a delta-application or sequence-gap bug,
     *                                 which is the whole reason this method exists.
     */
    public Quantity plusSigned(long deltaMicroContracts) {
        long result = microContracts + deltaMicroContracts;
        if (result < 0) {
            throw new IllegalArgumentException(
                    "book level would go negative: " + FixedPoint.format(microContracts, 2)
                            + " + " + FixedPoint.format(deltaMicroContracts, 2)
                            + " = " + FixedPoint.format(result, 2));
        }
        return new Quantity(result);
    }

    public Quantity plus(Quantity other) {
        return new Quantity(microContracts + other.microContracts);
    }

    public boolean isZero() {
        return microContracts == 0;
    }

    /** Renders the count, e.g. {@code "102.12"}. */
    public String toContractString() {
        return FixedPoint.format(microContracts, 2);
    }

    @Override
    public int compareTo(Quantity other) {
        return Long.compare(microContracts, other.microContracts);
    }

    @Override
    public String toString() {
        return toContractString();
    }
}
