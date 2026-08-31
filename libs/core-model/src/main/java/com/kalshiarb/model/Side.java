package com.kalshiarb.model;

/**
 * The two sides of a Kalshi binary contract.
 *
 * <p>Kalshi publishes a bid book for each side and no ask book at all. Asks are implied by
 * complementing the opposite side's bids, which makes {@link #opposite()} load-bearing rather
 * than decorative.
 */
public enum Side {
    YES("yes"),
    NO("no");

    private final String wireName;

    Side(String wireName) {
        this.wireName = wireName;
    }

    /** The lowercase name used in Kalshi JSON, e.g. the {@code yes_dollars} book key. */
    public String wireName() {
        return wireName;
    }

    public Side opposite() {
        return this == YES ? NO : YES;
    }

    public static Side fromWireName(String s) {
        if (YES.wireName.equals(s)) {
            return YES;
        }
        if (NO.wireName.equals(s)) {
            return NO;
        }
        throw new IllegalArgumentException("unknown side: " + s);
    }
}
