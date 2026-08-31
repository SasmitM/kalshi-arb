package com.kalshiarb.model;

/**
 * A Kalshi market ticker, e.g. {@code KXHIGHNY-26SEP01-B87.5} — the "87° to 88°" bracket.
 *
 * <p>Structure is {@code SERIES-EVENTDATE-STRIKE}. The strike suffix encodes the bracket:
 * {@code B87.5} is a {@code between} market, {@code T90} a {@code greater} / {@code less}
 * threshold. This type deliberately does <em>not</em> try to parse the strike out of the
 * ticker; strike semantics come from the market payload's {@code strike_type},
 * {@code floor_strike} and {@code cap_strike} fields, and those fields are off by one from the
 * human-readable sub-title (real example: {@code T83} carries {@code cap_strike: 83} but means
 * "82° or below"). Deriving strikes from ticker text would bake that ambiguity into a value
 * type.
 *
 * @param value the raw ticker string
 */
public record MarketTicker(String value) implements Comparable<MarketTicker> {

    public MarketTicker {
        Tickers.requireNonBlank(value, "market ticker");
    }

    public static MarketTicker of(String value) {
        return new MarketTicker(value);
    }

    /** The event this market belongs to: everything before the last {@code '-'}. */
    public EventTicker eventTicker() {
        int dash = value.lastIndexOf('-');
        if (dash <= 0 || value.indexOf('-') == dash) {
            throw new IllegalArgumentException(
                    "market ticker is not SERIES-EVENTDATE-STRIKE: " + value);
        }
        return new EventTicker(value.substring(0, dash));
    }

    public SeriesTicker seriesTicker() {
        return eventTicker().seriesTicker();
    }

    @Override
    public int compareTo(MarketTicker other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
