package com.kalshiarb.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FixedPointTest {

    @DisplayName("inconsistent decimal places all parse to the same micro value")
    @ParameterizedTest(name = "{0} -> {1} micros")
    @CsvSource({
            // The three forms named in CLAUDE.md's done-when criteria.
            "0.0800, 80000",
            "0.960,  960000",
            "0.9,    900000",
            // Same number, four ways of writing it — the point of parsing structurally.
            "0.5,      500000",
            "0.50,     500000",
            "0.500,    500000",
            "0.500000, 500000",
            // Bounds and shapes seen in real payloads.
            "0.0000, 0",
            "0.0100, 10000",
            "1.0000, 1000000",
            "10.0000, 10000000",
            "0.9400, 940000",
            // Counts (_fp fields) are fractional.
            "300.00, 300000000",
            "102.12, 102120000",
            "19.41,  19410000",
            // No fractional part at all.
            "0, 0",
            "1, 1000000",
            "7, 7000000",
            // Sub-penny precision, which is why the integer fields are unusable.
            "0.005,    5000",
            "0.000001, 1",
    })
    void parsesFixedPointStrings(String input, long expectedMicros) {
        assertThat(FixedPoint.parseMicros(input)).isEqualTo(expectedMicros);
    }

    @Test
    @DisplayName("negative values parse (deltas and money can be negative)")
    void parsesNegatives() {
        assertThat(FixedPoint.parseMicros("-0.0800")).isEqualTo(-80_000L);
        assertThat(FixedPoint.parseMicros("-300.00")).isEqualTo(-300_000_000L);
        assertThat(FixedPoint.parseMicros("-0")).isEqualTo(0L);
    }

    @DisplayName("malformed input is rejected, never silently coerced")
    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {
            "",        // empty
            "-",       // sign only
            ".5",      // no integer digit
            "1.",      // trailing point
            "0.08.0",  // two points
            "abc",
            "0.08a",
            "0x08",
            " 0.08",   // leading space
            "0.08 ",   // trailing space
            "+0.08",   // explicit plus not used by Kalshi
            "1e-2",    // scientific notation
            "1,000.00" // thousands separator
    })
    void rejectsMalformed(String input) {
        assertThatThrownBy(() -> FixedPoint.parseMicros(input))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null is rejected with a clear message")
    void rejectsNull() {
        assertThatThrownBy(() -> FixedPoint.parseMicros(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    @DisplayName("trailing zeros below micro precision are harmless, non-zero digits throw")
    void refusesToTruncatePrecision() {
        // 7 decimals but the 7th is a zero: no information lost.
        assertThat(FixedPoint.parseMicros("0.0800000")).isEqualTo(80_000L);
        assertThat(FixedPoint.parseMicros("0.5000000000")).isEqualTo(500_000L);

        // 7 decimals with a non-zero digit: refuse rather than round or truncate.
        assertThatThrownBy(() -> FixedPoint.parseMicros("0.0000001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refusing to truncate");
    }

    @Test
    @DisplayName("absurdly large whole parts are rejected instead of overflowing")
    void rejectsOverflow() {
        assertThatThrownBy(() -> FixedPoint.parseMicros("99999999999999999999.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
    }

    @DisplayName("format emits Kalshi's 4-decimal wire shape, widening only when needed")
    @ParameterizedTest(name = "{0} micros -> {1}")
    @CsvSource({
            "80000,   0.0800",
            "960000,  0.9600",
            "900000,  0.9000",
            "1000000, 1.0000",
            "0,       0.0000",
            "10000000, 10.0000",
            "5000,    0.0050",
            "12345,   0.012345",
            "1,       0.000001",
            "-80000,  -0.0800",
    })
    void formatsMicros(long micros, String expected) {
        assertThat(FixedPoint.format(micros, 4)).isEqualTo(expected);
    }

    @Test
    @DisplayName("parse and format round-trip for every real payload value seen")
    void roundTrips() {
        for (String s : new String[] {
                "0.0100", "0.0800", "0.9400", "0.9600", "1.0000", "0.0000", "0.4800", "0.5000"}) {
            long micros = FixedPoint.parseMicros(s);
            assertThat(FixedPoint.format(micros, 4)).isEqualTo(s);
            assertThat(FixedPoint.parseMicros(FixedPoint.format(micros, 4))).isEqualTo(micros);
        }
    }

    @Test
    @DisplayName("counts format at 2 decimals like the _fp wire fields")
    void formatsCounts() {
        assertThat(FixedPoint.format(300_000_000L, 2)).isEqualTo("300.00");
        assertThat(FixedPoint.format(102_120_000L, 2)).isEqualTo("102.12");
        assertThat(FixedPoint.format(19_410_000L, 2)).isEqualTo("19.41");
    }
}
