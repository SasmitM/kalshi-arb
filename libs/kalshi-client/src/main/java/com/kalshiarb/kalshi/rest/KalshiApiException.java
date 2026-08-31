package com.kalshiarb.kalshi.rest;

/**
 * A non-2xx response from Kalshi.
 *
 * <p>{@link #isRateLimited()} exists because 429s here are unusually unhelpful: Kalshi sends no
 * {@code Retry-After} and no rate-limit headers, so there is nothing to react to. The only
 * workable strategy is to mirror the token bucket client-side and treat a 429 as evidence the
 * local model has drifted.
 */
public final class KalshiApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String method;
    private final String path;
    private final String body;

    KalshiApiException(int statusCode, String method, String path, String body) {
        super("Kalshi " + method + " " + path + " returned HTTP " + statusCode
                + (body == null || body.isBlank() ? "" : ": " + truncate(body)));
        this.statusCode = statusCode;
        this.method = method;
        this.path = path;
        this.body = body;
    }

    public int statusCode() {
        return statusCode;
    }

    public String method() {
        return method;
    }

    public String path() {
        return path;
    }

    public String body() {
        return body;
    }

    /** 401/403 — for a signed call this almost always means a malformed canonical message. */
    public boolean isAuthFailure() {
        return statusCode == 401 || statusCode == 403;
    }

    /** 429 — carries no {@code Retry-After}, so back off on a client-side schedule. */
    public boolean isRateLimited() {
        return statusCode == 429;
    }

    private static String truncate(String s) {
        return s.length() <= 512 ? s : s.substring(0, 512) + "... (truncated)";
    }
}
