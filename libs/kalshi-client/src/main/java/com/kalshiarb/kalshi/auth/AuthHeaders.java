package com.kalshiarb.kalshi.auth;

import java.net.http.HttpRequest;

/**
 * The three headers Kalshi requires on an authenticated request.
 *
 * @param accessKey the API Key ID (a UUID; identifying, not secret)
 * @param timestamp Unix time in milliseconds, as a decimal string
 * @param signature base64 RSA-PSS signature over {@code timestamp + METHOD + path}
 */
public record AuthHeaders(String accessKey, String timestamp, String signature) {

    public static final String HEADER_ACCESS_KEY = "KALSHI-ACCESS-KEY";
    public static final String HEADER_TIMESTAMP = "KALSHI-ACCESS-TIMESTAMP";
    public static final String HEADER_SIGNATURE = "KALSHI-ACCESS-SIGNATURE";

    /** Applies all three headers to a request under construction. */
    public HttpRequest.Builder applyTo(HttpRequest.Builder builder) {
        return builder
                .header(HEADER_ACCESS_KEY, accessKey)
                .header(HEADER_TIMESTAMP, timestamp)
                .header(HEADER_SIGNATURE, signature);
    }

    /** Redacts the signature; it is short-lived but there is no reason to log it. */
    @Override
    public String toString() {
        return "AuthHeaders[timestamp=" + timestamp + ", signature=<" + signature.length()
                + " chars>]";
    }
}
