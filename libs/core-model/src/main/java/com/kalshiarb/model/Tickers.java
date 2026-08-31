package com.kalshiarb.model;

/** Shared validation for the three ticker value types. */
final class Tickers {

    private Tickers() {
    }

    static void requireNonBlank(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " is null or blank");
        }
    }
}
