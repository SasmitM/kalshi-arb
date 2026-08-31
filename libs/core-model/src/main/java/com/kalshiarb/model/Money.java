package com.kalshiarb.model;

/**
 * A signed dollar amount in micro-dollars, where $1.00 == 1_000_000.
 *
 * <p>Distinct from {@link Price} on purpose. A price is bounded to {@code [0, $1.00]}; money
 * is unbounded and may be negative — account balances, fee totals, and the fee-adjusted edge
 * of a losing opportunity are all money. The $0.96 five-bracket example in CLAUDE.md must be
 * able to produce a negative result.
 *
 * @param micros amount in micro-dollars, possibly negative
 */
public record Money(long micros) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    /** Parses a Kalshi {@code _dollars} field, e.g. {@code "10.0000"} from /portfolio/balance. */
    public static Money fromFixedPointString(String dollars) {
        return new Money(FixedPoint.parseMicros(dollars));
    }

    public static Money ofCents(long cents) {
        return new Money(cents * 10_000L);
    }

    public static Money of(Price price) {
        return new Money(price.micros());
    }

    public Money plus(Money other) {
        return new Money(micros + other.micros);
    }

    public Money minus(Money other) {
        return new Money(micros - other.micros);
    }

    public Money negated() {
        return new Money(-micros);
    }

    public boolean isNegative() {
        return micros < 0;
    }

    public String toDollarString() {
        return FixedPoint.format(micros, 4);
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(micros, other.micros);
    }

    @Override
    public String toString() {
        return (micros < 0 ? "-$" + FixedPoint.format(-micros, 4) : "$" + toDollarString());
    }
}
