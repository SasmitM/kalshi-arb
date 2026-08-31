package com.kalshiarb.kalshi.rest.dto;

import com.kalshiarb.model.EventTicker;
import com.kalshiarb.model.SeriesTicker;

/**
 * One event from {@code GET /events} or {@code GET /events/{ticker}}.
 *
 * <p>The event is the unit the constraint algebra operates on. {@link #mutuallyExclusive} is the
 * field that says whether the exhaustive-bracket rule applies at all: when true, exactly one of
 * the event's markets can settle YES, so their prices must sum to $1.00. The six real
 * {@code KXHIGHNY-26SEP01} brackets tile the temperature range with no gap or overlap
 * (≤82, 83-84, 85-86, 87-88, 89-90, ≥91) and carry {@code mutually_exclusive: true}.
 *
 * <p>A detector must never assume exclusivity from ticker shape alone — this flag is the
 * authority, and reading it wrong means inventing constraints that do not hold.
 *
 * @param eventTicker        e.g. {@code KXHIGHNY-26SEP01}
 * @param seriesTicker       e.g. {@code KXHIGHNY}
 * @param title              human title
 * @param subTitle           human sub-title, e.g. {@code "On Sep 1, 2026"}
 * @param category           e.g. {@code "Climate and Weather"}
 * @param mutuallyExclusive  whether exactly one market under this event settles YES
 * @param strikeDate         settlement reference date
 * @param strikePeriod       period descriptor; blank for daily events
 * @param collateralReturnType e.g. {@code "MECNET"}
 * @param exchangeIndex      exchange shard 0-3
 */
public record EventSummary(
        String eventTicker,
        String seriesTicker,
        String title,
        String subTitle,
        String category,
        Boolean mutuallyExclusive,
        String strikeDate,
        String strikePeriod,
        String collateralReturnType,
        Integer exchangeIndex) {

    public EventTicker event() {
        return EventTicker.of(eventTicker);
    }

    public SeriesTicker series() {
        return SeriesTicker.of(seriesTicker);
    }

    /**
     * Whether this event's markets form an exhaustive, mutually exclusive set.
     *
     * <p>Absent is treated as false: assuming exclusivity when the API did not assert it would
     * manufacture constraint violations out of nothing.
     */
    public boolean isMutuallyExclusive() {
        return Boolean.TRUE.equals(mutuallyExclusive);
    }
}
