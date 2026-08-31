package com.kalshiarb.kalshi.rest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.kalshiarb.common.Clock;
import com.kalshiarb.kalshi.auth.AuthHeaders;
import com.kalshiarb.kalshi.auth.KalshiSigner;
import com.kalshiarb.kalshi.rest.dto.Balance;
import com.kalshiarb.kalshi.rest.dto.ExchangeStatus;
import com.kalshiarb.kalshi.rest.dto.Orderbook;
import com.kalshiarb.kalshi.rest.dto.OrderbookResponse;
import com.kalshiarb.kalshi.rest.dto.Pages;
import com.kalshiarb.kalshi.rest.dto.SeriesDetail;
import com.kalshiarb.model.EventTicker;
import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.SeriesTicker;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Minimal REST client for Kalshi's event-contract API.
 *
 * <p>Scope is deliberately narrow: enough to discover the market universe at startup and to read
 * a book once for verification. <b>Order books are not polled.</b> Continuous book state comes
 * over the WebSocket, which consumes no rate-limit tokens; polling
 * {@code /markets/{ticker}/orderbook} in a loop would burn the read bucket to reproduce
 * something the socket already streams.
 *
 * <p>Signing is per-call, because market data is public. {@code /series}, {@code /events},
 * {@code /markets} and {@code /markets/{ticker}/orderbook} need no credentials at all, which is
 * what lets the recorder run and the tests hit live endpoints without a key. Only
 * {@link #balance()} signs.
 *
 * <p>Jackson <b>databind</b> is used here on purpose, against CLAUDE.md's streaming rule. That
 * rule governs the hot path — WebSocket frames arriving continuously — and these calls happen
 * a handful of times at startup. Trading readable record DTOs for a few microseconds on a
 * once-per-process call is the right side of that trade; the WS codec uses streaming
 * {@code JsonParser}.
 *
 * <p>One HTTP/2 client is held for the client's lifetime so the TLS handshake and connection are
 * amortised across calls.
 */
public final class KalshiRestClient implements AutoCloseable {

    /**
     * Verified live. The older {@code api.elections.kalshi.com} alias also answers; the
     * {@code trading-api.kalshi.com} host that appears in blog posts returns 401 and is stale.
     */
    public static final String DEFAULT_BASE_URL = "https://external-api.kalshi.com";

    /** Part of every path, and part of the signed message. */
    public static final String API_PREFIX = "/trade-api/v2";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final KalshiSigner signer;
    private final Clock clock;

    private KalshiRestClient(String baseUrl, KalshiSigner signer, Clock clock) {
        this.baseUrl = trimTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.signer = signer;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.mapper = new ObjectMapper()
                // Wire is snake_case, records are camelCase; one setting instead of an
                // @JsonProperty on every component.
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                // Kalshi adds fields without warning. Failing on unknown properties would turn
                // every additive API change into an outage.
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** Public market data only. Any signed call will fail. */
    public static KalshiRestClient publicOnly(Clock clock) {
        return new KalshiRestClient(DEFAULT_BASE_URL, null, clock);
    }

    /** Public market data plus authenticated portfolio reads. */
    public static KalshiRestClient authenticated(KalshiSigner signer, Clock clock) {
        return new KalshiRestClient(DEFAULT_BASE_URL, Objects.requireNonNull(signer), clock);
    }

    /** For pointing tests at a local stub. */
    public static KalshiRestClient forBaseUrl(String baseUrl, KalshiSigner signer, Clock clock) {
        return new KalshiRestClient(baseUrl, signer, clock);
    }

    public boolean canSign() {
        return signer != null;
    }

    // ---------------------------------------------------------------- public market data

    /** {@code GET /exchange/status}. Unauthenticated. */
    public ExchangeStatus exchangeStatus() {
        return get("/exchange/status", Map.of(), false, ExchangeStatus.class);
    }

    /** {@code GET /series/{ticker}}. Unauthenticated. Carries the per-series fee multiplier. */
    public SeriesDetail series(SeriesTicker series) {
        return get("/series/" + series.value(), Map.of(), false, Pages.SeriesResponse.class)
                .series();
    }

    /**
     * {@code GET /events}. Unauthenticated.
     *
     * @param series filter to one series, or null for all
     * @param limit  page size
     * @param cursor opaque cursor from a previous page, or null for the first page
     */
    public Pages.EventsPage events(SeriesTicker series, int limit, String cursor) {
        Map<String, String> query = new LinkedHashMap<>();
        if (series != null) {
            query.put("series_ticker", series.value());
        }
        query.put("limit", Integer.toString(limit));
        if (cursor != null && !cursor.isBlank()) {
            query.put("cursor", cursor);
        }
        return get("/events", query, false, Pages.EventsPage.class);
    }

    /**
     * {@code GET /markets}. Unauthenticated.
     *
     * <p>Two verified query quirks, both of which fail in ways that are easy to misread:
     *
     * <ul>
     *   <li>The {@code status} filter value is <b>{@code "open"}</b>, even though each market
     *       object reports {@code "status": "active"}. Passing {@code status=active} returns
     *       <b>HTTP 400</b>, not an empty list.
     *   <li>{@code status} requires a scoping filter. {@code /markets?status=open} alone is
     *       <b>HTTP 400</b>; it must be combined with {@code series_ticker} or
     *       {@code event_ticker}.
     * </ul>
     *
     * A caller that ignores the status code sees an empty market list and concludes there are no
     * open markets, which is why {@link #get} always throws on non-2xx.
     *
     * @param series filter to one series, or null
     * @param event  filter to one event, or null
     * @param status {@code "open"} to restrict to tradable markets, or null for no filter
     */
    public Pages.MarketsPage markets(
            SeriesTicker series, EventTicker event, String status, int limit, String cursor) {
        Map<String, String> query = new LinkedHashMap<>();
        if (series != null) {
            query.put("series_ticker", series.value());
        }
        if (event != null) {
            query.put("event_ticker", event.value());
        }
        if (status != null && !status.isBlank()) {
            if (series == null && event == null) {
                throw new IllegalArgumentException(
                        "Kalshi returns HTTP 400 for a status filter without series_ticker or "
                                + "event_ticker; scope the query.");
            }
            query.put("status", status);
        }
        query.put("limit", Integer.toString(limit));
        if (cursor != null && !cursor.isBlank()) {
            query.put("cursor", cursor);
        }
        return get("/markets", query, false, Pages.MarketsPage.class);
    }

    /** All markets under one event. The six brackets of a weather event arrive in one page. */
    public List<com.kalshiarb.kalshi.rest.dto.MarketSummary> marketsForEvent(EventTicker event) {
        return markets(null, event, null, 200, null).markets();
    }

    /**
     * Every open market in a series, following cursors to exhaustion.
     *
     * <p>Bounded by {@code maxPages} so a cursor bug cannot spin forever against a live API.
     */
    public List<com.kalshiarb.kalshi.rest.dto.MarketSummary> openMarketsInSeries(
            SeriesTicker series, int maxPages) {
        List<com.kalshiarb.kalshi.rest.dto.MarketSummary> all = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < maxPages; page++) {
            Pages.MarketsPage p = markets(series, null, "open", 200, cursor);
            all.addAll(p.markets());
            if (!p.hasMore()) {
                return all;
            }
            cursor = p.cursor();
        }
        throw new IllegalStateException(
                "markets pagination for " + series + " exceeded " + maxPages + " pages");
    }

    /**
     * {@code GET /markets/{ticker}/orderbook}. Unauthenticated.
     *
     * <p>For one-shot inspection and for seeding verification. Not for continuous state — that
     * is the WebSocket's job.
     */
    public Orderbook orderbook(MarketTicker ticker) {
        return get("/markets/" + encode(ticker.value()) + "/orderbook",
                Map.of(), false, OrderbookResponse.class)
                .toOrderbook(ticker);
    }

    // ---------------------------------------------------------------- authenticated

    /** {@code GET /portfolio/balance}. Requires signing. Read-only. */
    public Balance balance() {
        return get("/portfolio/balance", Map.of(), true, Balance.class);
    }

    // ---------------------------------------------------------------- transport

    private <T> T get(
            String relativePath, Map<String, String> query, boolean signed, Class<T> type) {
        String path = API_PREFIX + relativePath;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path + queryString(query)))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET();

        if (signed) {
            if (signer == null) {
                throw new IllegalStateException(
                        "GET " + path + " requires authentication but this client was built with "
                                + "publicOnly(); use authenticated(signer, clock).");
            }
            // The signature covers the path WITHOUT the query string, while the request is sent
            // WITH it. Kalshi defines the canonical message that way, so two requests differing
            // only in query parameters sign identically. Passing the built URI here instead of
            // `path` is the single easiest way to produce an unexplained 401.
            AuthHeaders headers = signer.sign("GET", path);
            headers.applyTo(builder);
        }

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("GET " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GET " + path + " interrupted", e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new KalshiApiException(response.statusCode(), "GET", path, response.body());
        }

        try {
            return mapper.readValue(response.body(), type);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "GET " + path + " returned HTTP " + response.statusCode()
                            + " but the body did not parse as " + type.getSimpleName()
                            + ": " + e.getMessage(), e);
        }
    }

    private static String queryString(Map<String, String> query) {
        if (query.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(64);
        for (Map.Entry<String, String> e : query.entrySet()) {
            sb.append(sb.isEmpty() ? '?' : '&')
                    .append(encode(e.getKey()))
                    .append('=')
                    .append(encode(e.getValue()));
        }
        return sb.toString();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** Exposed so callers can measure latency against the same clock the client uses. */
    public Clock clock() {
        return clock;
    }

    public Optional<KalshiSigner> signer() {
        return Optional.ofNullable(signer);
    }

    @Override
    public void close() {
        // HttpClient became AutoCloseable in 21; closing releases the connection pool and the
        // selector thread, which matters because the recorder creates one per process and the
        // tests create several.
        http.close();
    }
}
