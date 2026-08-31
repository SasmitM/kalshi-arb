package com.kalshiarb.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PriceLevelTest {

    @Test
    @DisplayName("parses a wire pair [price_dollars, count_fp]")
    void parsesWirePair() {
        PriceLevel level = PriceLevel.fromFixedPointStrings("0.0800", "300.00");

        assertThat(level.price()).isEqualTo(Price.ofCents(8));
        assertThat(level.quantity()).isEqualTo(Quantity.ofContracts(300));
        assertThat(level).hasToString("300.00 @ $0.0800");
    }

    @Test
    @DisplayName("parses the ragged real levels, including fractional counts")
    void parsesRealLevels() {
        // Straight out of orderbook_KXHIGHNY-26SEP01-T83.json.
        PriceLevel a = PriceLevel.fromFixedPointStrings("0.0600", "102.12");
        PriceLevel b = PriceLevel.fromFixedPointStrings("0.4900", "150.44");

        assertThat(a.quantity().microContracts()).isEqualTo(102_120_000L);
        assertThat(b.quantity().microContracts()).isEqualTo(150_440_000L);
    }

    @Test
    @DisplayName("notional is price times size, exact in micro-dollars")
    void notional() {
        // 300 contracts at $0.08 = $24.00
        assertThat(PriceLevel.fromFixedPointStrings("0.0800", "300.00").notional())
                .isEqualTo(Money.fromFixedPointString("24.0000"));

        // Fractional size: 102.12 contracts at $0.06 = $6.1272
        assertThat(PriceLevel.fromFixedPointStrings("0.0600", "102.12").notional().toDollarString())
                .isEqualTo("6.1272");

        // 1 contract at $1.00 = $1.00
        assertThat(PriceLevel.fromFixedPointStrings("1.0000", "1.00").notional())
                .isEqualTo(Money.ofCents(100));
    }

    @Test
    @DisplayName("side names match the order-book JSON keys")
    void sideWireNames() {
        assertThat(Side.YES.wireName()).isEqualTo("yes");
        assertThat(Side.NO.wireName()).isEqualTo("no");
        assertThat(Side.fromWireName("no")).isEqualTo(Side.NO);
        assertThat(Side.YES.opposite()).isEqualTo(Side.NO);
        assertThat(Side.NO.opposite().opposite()).isEqualTo(Side.NO);
    }
}
