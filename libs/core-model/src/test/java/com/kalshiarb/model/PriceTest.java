package com.kalshiarb.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PriceTest {

    @DisplayName("the three decimal shapes from CLAUDE.md yield the right micro-dollars")
    @ParameterizedTest(name = "{0} -> {1} micros")
    @CsvSource({
            "0.0800, 80000",
            "0.960,  960000",
            "0.9,    900000",
    })
    void parsesTheSpecifiedShapes(String input, long expectedMicros) {
        assertThat(Price.fromFixedPointString(input).micros()).isEqualTo(expectedMicros);
    }

    @Test
    @DisplayName("complement() is $1.00 - price and round-trips")
    void complementRoundTrips() {
        // The implied-ask conversion from CLAUDE.md section 5: a NO bid at $0.45 is a YES ask
        // at $0.55.
        Price noBid = Price.fromFixedPointString("0.45");
        assertThat(noBid.complement()).isEqualTo(Price.fromFixedPointString("0.55"));
        assertThat(noBid.complement().complement()).isEqualTo(noBid);

        for (String s : new String[] {"0.0000", "0.0100", "0.0800", "0.5000", "0.9400", "1.0000"}) {
            Price p = Price.fromFixedPointString(s);
            assertThat(p.complement().complement()).isEqualTo(p);
            assertThat(p.plus(p.complement())).isEqualTo(Price.ONE_DOLLAR);
        }
    }

    @Test
    @DisplayName("complement of the endpoints is exact")
    void complementEndpoints() {
        assertThat(Price.ZERO.complement()).isEqualTo(Price.ONE_DOLLAR);
        assertThat(Price.ONE_DOLLAR.complement()).isEqualTo(Price.ZERO);
    }

    @Test
    @DisplayName("toDollarString() reproduces the wire format")
    void formatsToWireFormat() {
        assertThat(Price.fromFixedPointString("0.0800").toDollarString()).isEqualTo("0.0800");
        // Normalises the ragged inputs onto one canonical output.
        assertThat(Price.fromFixedPointString("0.960").toDollarString()).isEqualTo("0.9600");
        assertThat(Price.fromFixedPointString("0.9").toDollarString()).isEqualTo("0.9000");
        assertThat(Price.ofCents(8).toDollarString()).isEqualTo("0.0800");
    }

    @Test
    @DisplayName("prices outside [0, $1.00] are rejected — a contract cannot settle there")
    void rejectsOutOfRange() {
        assertThatThrownBy(() -> new Price(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of [0, $1.00]");
        assertThatThrownBy(() -> new Price(FixedPoint.ONE + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of [0, $1.00]");
        assertThatThrownBy(() -> Price.fromFixedPointString("1.0001"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("exhaustive bracket prices sum exactly, with no floating-point drift")
    void bracketPricesSumExactly() {
        // The six real KXHIGHNY-26SEP01 yes-bids. In double arithmetic
        // 0.05 + 0.13 + 0.50 + 0.00 + 0.29 + 0.01 does not equal 0.98; in micros it must.
        Price sum = Price.ZERO;
        for (String s : new String[] {"0.0500", "0.1300", "0.5000", "0.0000", "0.2900", "0.0100"}) {
            sum = sum.plus(Price.fromFixedPointString(s));
        }
        assertThat(sum.micros()).isEqualTo(980_000L);
        assertThat(sum.toDollarString()).isEqualTo("0.9800");
        assertThat(sum.isLessThan(Price.ONE_DOLLAR)).isTrue();
    }

    @Test
    @DisplayName("ordering is by micros")
    void ordersByMicros() {
        assertThat(Price.ofCents(5)).isLessThan(Price.ofCents(50));
        assertThat(Price.ofCents(50).isGreaterThan(Price.ofCents(5))).isTrue();
        assertThat(Price.fromFixedPointString("0.5"))
                .isEqualByComparingTo(Price.fromFixedPointString("0.5000"));
    }
}
