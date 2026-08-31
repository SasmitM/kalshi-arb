package com.kalshiarb.model;

/**
 * Parses and formats Kalshi's fixed-point decimal strings using integer arithmetic only.
 *
 * <p>Kalshi is mid-migration between legacy integer fields and fixed-point string fields
 * ({@code _dollars}, {@code _fp}). The string fields are authoritative because the integer
 * fields lose precision under sub-penny pricing. The number of decimal places is
 * <em>inconsistent across responses</em> — real payloads contain {@code "0.0800"},
 * {@code "0.960"} and {@code "0.9"} for equivalent-looking quantities — so this parser is
 * structural and never pattern-matches on decimal count.
 *
 * <p>The canonical internal unit is <b>micros</b>: 1.0 is represented as {@value #ONE}.
 * {@code double} and {@code float} are never used here or anywhere near a price, because
 * the detector sums brackets and compares the total against exactly $1.00; binary floating
 * point cannot represent $0.01 and the accumulated error is the same order of magnitude as
 * the edge we are looking for. {@link java.math.BigDecimal} is correct but allocates per
 * operation, so it is kept off the hot path entirely.
 */
public final class FixedPoint {

    /** Micros per whole unit: 1.0 == 1_000_000 micros. */
    public static final long ONE = 1_000_000L;

    /** Number of decimal digits {@link #ONE} can represent exactly. */
    public static final int SCALE = 6;

    /**
     * Largest whole part we accept. Bounded well below {@code Long.MAX_VALUE / ONE} so that
     * the multiply in {@link #parseMicros} cannot overflow silently.
     */
    private static final long MAX_WHOLE = Long.MAX_VALUE / ONE / 10;

    private FixedPoint() {
    }

    /**
     * Parses a fixed-point decimal string to signed micros.
     *
     * <p>Accepts an optional leading {@code -}, at least one integer digit, and an optional
     * fractional part with at least one digit. Trailing zeros beyond {@link #SCALE} decimal
     * places are tolerated (they carry no information); a <em>non-zero</em> digit below micro
     * precision is rejected rather than truncated, because silently dropping precision from a
     * price is exactly the class of bug this type exists to prevent.
     *
     * @throws IllegalArgumentException if the input is null, empty, malformed, out of range,
     *                                 or carries non-zero precision finer than micros
     */
    public static long parseMicros(String s) {
        if (s == null) {
            throw new IllegalArgumentException("fixed-point string is null");
        }
        int len = s.length();
        if (len == 0) {
            throw new IllegalArgumentException("fixed-point string is empty");
        }

        int i = 0;
        boolean negative = false;
        if (s.charAt(0) == '-') {
            negative = true;
            i = 1;
        }

        long whole = 0;
        int wholeDigits = 0;
        while (i < len && s.charAt(i) != '.') {
            int d = digitAt(s, i);
            whole = whole * 10 + d;
            if (whole > MAX_WHOLE) {
                throw new IllegalArgumentException("fixed-point value out of range: " + s);
            }
            wholeDigits++;
            i++;
        }
        if (wholeDigits == 0) {
            // Rejects "", "-", ".5" — an explicit leading zero is required.
            throw new IllegalArgumentException(
                    "fixed-point string needs at least one integer digit: " + s);
        }

        long frac = 0;
        int fracDigits = 0;
        if (i < len) {
            i++; // consume '.'
            if (i == len) {
                throw new IllegalArgumentException(
                        "fixed-point string has a trailing decimal point: " + s);
            }
            while (i < len) {
                int d = digitAt(s, i);
                if (fracDigits < SCALE) {
                    frac = frac * 10 + d;
                    fracDigits++;
                } else if (d != 0) {
                    throw new IllegalArgumentException(
                            "fixed-point string carries precision finer than 1e-" + SCALE
                                    + " micros, refusing to truncate: " + s);
                }
                i++;
            }
        }

        // Left-align the fraction to exactly SCALE digits: "0.9" -> 900000, "0.960" -> 960000.
        for (; fracDigits < SCALE; fracDigits++) {
            frac *= 10;
        }

        long micros = whole * ONE + frac;
        return negative ? -micros : micros;
    }

    private static int digitAt(String s, int i) {
        char c = s.charAt(i);
        if (c < '0' || c > '9') {
            throw new IllegalArgumentException(
                    "unexpected character '" + c + "' at index " + i + " in fixed-point string: " + s);
        }
        return c - '0';
    }

    /**
     * Formats signed micros back to a fixed-point decimal string.
     *
     * <p>Emits at least {@code minDecimals} places and at most {@link #SCALE}, trimming only
     * trailing zeros that sit beyond {@code minDecimals}. With {@code minDecimals == 4} this
     * reproduces Kalshi's usual price wire format ({@code 80_000 -> "0.0800"}) while still
     * representing sub-penny values exactly ({@code 12_345 -> "0.012345"}).
     */
    public static String format(long micros, int minDecimals) {
        if (minDecimals < 0 || minDecimals > SCALE) {
            throw new IllegalArgumentException("minDecimals must be in [0, " + SCALE + "]");
        }
        StringBuilder sb = new StringBuilder(24);
        long abs;
        if (micros < 0) {
            sb.append('-');
            abs = -micros; // safe: parseMicros bounds magnitude well below Long.MIN_VALUE
        } else {
            abs = micros;
        }
        sb.append(abs / ONE);

        long frac = abs % ONE;
        char[] digits = new char[SCALE];
        for (int p = SCALE - 1; p >= 0; p--) {
            digits[p] = (char) ('0' + (frac % 10));
            frac /= 10;
        }
        int keep = SCALE;
        while (keep > minDecimals && digits[keep - 1] == '0') {
            keep--;
        }
        if (keep > 0) {
            sb.append('.').append(digits, 0, keep);
        }
        return sb.toString();
    }
}
