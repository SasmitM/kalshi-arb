package com.kalshiarb.kalshi.rest.dto;

import java.util.List;

/**
 * Cursor-paginated list responses.
 *
 * <p>Kalshi returns an opaque {@code cursor} string; an empty or absent cursor means the last
 * page. The cursor is not a page number and must be echoed back verbatim.
 */
public final class Pages {

    private Pages() {
    }

    /**
     * {@code GET /markets} — {@code {"cursor": "...", "markets": [...]}}.
     *
     * @param markets markets on this page
     * @param cursor  opaque cursor for the next page, blank when exhausted
     */
    public record MarketsPage(List<MarketSummary> markets, String cursor) {

        public MarketsPage {
            markets = markets == null ? List.of() : List.copyOf(markets);
        }

        public boolean hasMore() {
            return cursor != null && !cursor.isBlank();
        }
    }

    /**
     * {@code GET /events} — {@code {"cursor": "...", "events": [...], "milestones": [...]}}.
     *
     * <p>{@code milestones} is present in real responses and deliberately not modelled; it is
     * unrelated to the constraint algebra.
     *
     * @param events events on this page
     * @param cursor opaque cursor for the next page, blank when exhausted
     */
    public record EventsPage(List<EventSummary> events, String cursor) {

        public EventsPage {
            events = events == null ? List.of() : List.copyOf(events);
        }

        public boolean hasMore() {
            return cursor != null && !cursor.isBlank();
        }
    }

    /** {@code GET /series/{ticker}} — the series object is nested under {@code "series"}. */
    public record SeriesResponse(SeriesDetail series) {
    }

    /** {@code GET /events/{ticker}} — the event object is nested under {@code "event"}. */
    public record EventResponse(EventSummary event, List<MarketSummary> markets) {
    }
}
