package com.kalshiarb.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QuantityTest {

    @Test
    @DisplayName("fractional counts from real payloads parse exactly")
    void parsesFractionalCounts() {
        assertThat(Quantity.fromFixedPointString("300.00").microContracts())
                .isEqualTo(300_000_000L);
        assertThat(Quantity.fromFixedPointString("102.12").microContracts())
                .isEqualTo(102_120_000L);
        assertThat(Quantity.fromFixedPointString("19.41").microContracts())
                .isEqualTo(19_410_000L);
        assertThat(Quantity.fromFixedPointString("2.35").toContractString()).isEqualTo("2.35");
    }

    @Test
    @DisplayName("a positive delta adds to the level")
    void appliesPositiveDelta() {
        Quantity level = Quantity.fromFixedPointString("100.00");
        assertThat(level.plusSigned(FixedPoint.parseMicros("25.50")).toContractString())
                .isEqualTo("125.50");
    }

    @Test
    @DisplayName("a negative delta subtracts; reaching exactly zero is legal")
    void appliesNegativeDelta() {
        Quantity level = Quantity.fromFixedPointString("100.00");
        assertThat(level.plusSigned(FixedPoint.parseMicros("-40.00")).toContractString())
                .isEqualTo("60.00");

        Quantity emptied = level.plusSigned(FixedPoint.parseMicros("-100.00"));
        assertThat(emptied.isZero()).isTrue();
        assertThat(emptied).isEqualTo(Quantity.ZERO);
    }

    @Test
    @DisplayName("a delta that would push a level negative throws — never clamps to zero")
    void refusesToClampNegative() {
        Quantity level = Quantity.fromFixedPointString("100.00");
        assertThatThrownBy(() -> level.plusSigned(FixedPoint.parseMicros("-100.01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("would go negative");
    }

    @Test
    @DisplayName("a negative quantity cannot be constructed at all")
    void rejectsNegativeConstruction() {
        assertThatThrownBy(() -> new Quantity(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> Quantity.fromFixedPointString("-5.00"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ofContracts is a whole-contract shorthand")
    void ofContracts() {
        assertThat(Quantity.ofContracts(300)).isEqualTo(Quantity.fromFixedPointString("300.00"));
        assertThat(Quantity.ofContracts(1).toContractString()).isEqualTo("1.00");
    }
}
