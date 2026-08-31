package com.kalshiarb.model;

/**
 * A Kalshi event ticker, e.g. {@code KXHIGHNY-26SEP01} — one day's NYC high-temperature event.
 *
 * <p>The event is the unit that matters most in this project: the markets under a single event
 * with {@code mutually_exclusive: true} form the exhaustive bracket family whose prices must
 * sum to $1.00. A constraint group never spans events, which is why the WebSocket layer shards
 * subscriptions at this level — a sequence gap then costs one event's ~6 markets rather than
 * the whole watchlist.
 *
 * @param value the raw ticker string
 */
public record EventTicker(String value) implements Comparable<EventTicker> {

    public EventTicker {
        Tickers.requireNonBlank(value, "event ticker");
    }

    public static EventTicker of(String value) {
        return new EventTicker(value);
    }

    /** The series this event belongs to: everything before the first {@code '-'}. */
    public SeriesTicker seriesTicker() {
        int dash = value.indexOf('-');
        if (dash <= 0) {
            throw new IllegalArgumentException(
                    "event ticker has no series prefix, expected SERIES-DATE: " + value);
        }
        return new SeriesTicker(value.substring(0, dash));
    }

    @Override
    public int compareTo(EventTicker other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
