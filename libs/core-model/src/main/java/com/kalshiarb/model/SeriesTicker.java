package com.kalshiarb.model;

/**
 * A Kalshi series ticker, e.g. {@code KXHIGHNY} — "daily high temperature in New York City".
 *
 * <p>The coarsest of the three ticker levels: a series spans every event (day) it ever runs.
 *
 * @param value the raw ticker string
 */
public record SeriesTicker(String value) implements Comparable<SeriesTicker> {

    public SeriesTicker {
        Tickers.requireNonBlank(value, "series ticker");
    }

    public static SeriesTicker of(String value) {
        return new SeriesTicker(value);
    }

    @Override
    public int compareTo(SeriesTicker other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
