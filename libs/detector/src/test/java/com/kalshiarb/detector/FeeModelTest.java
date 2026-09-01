package com.kalshiarb.detector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kalshiarb.model.Money;
import com.kalshiarb.model.Price;
import com.kalshiarb.model.Quantity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FeeModelTest {

    private final FeeModel fees = FeeModel.weather();

    @Test
    @DisplayName("the parabola peaks at 1.75c per contract at $0.50 and collapses in the tails")
    void parabolaShape() {
        // The figures CLAUDE.md pins, which is what makes a constant edge threshold wrong.
        assertThat(fees.feePerContract(Price.ofCents(50)).micros()).isEqualTo(17_500);
        assertThat(fees.feePerContract(Price.ofCents(10)).micros()).isEqualTo(6_300);
        assertThat(fees.feePerContract(Price.ofCents(5)).micros()).isEqualTo(3_325);

        // Symmetric about $0.50, since P(1-P) is.
        assertThat(fees.feePerContract(Price.ofCents(30)))
                .isEqualTo(fees.feePerContract(Price.ofCents(70)));

        // Zero at the boundaries: a certainty costs nothing to trade.
        assertThat(fees.feePerContract(Price.ZERO)).isEqualTo(Money.ZERO);
        assertThat(fees.feePerContract(Price.ONE_DOLLAR)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("the ceiling applies to the ORDER, not per contract")
    void ceilingIsOrderLevel() {
        // CLAUDE.md's example: ceil(0.07 * 8 * 0.10 * 0.90) is NOT 8 * ceil(0.07*0.10*0.90).
        Money orderFee = fees.orderFee(Quantity.ofContracts(8), Price.ofCents(10));

        // 0.07*8*0.10*0.90 = 0.0504 -> ceil to $0.06
        assertThat(orderFee).isEqualTo(Money.ofCents(6));

        // Per-contract ceiling would give 8 * $0.01 = $0.08, over 30% too much.
        Money wrongPerContract = Money.ofCents(8);
        assertThat(orderFee).isNotEqualTo(wrongPerContract);
        assertThat(orderFee.micros()).isLessThan(wrongPerContract.micros());
    }

    @Test
    @DisplayName("rounding taxes a one-contract order brutally — 59% overhead at $0.10")
    void smallOrdersPayDisproportionately() {
        Money owed = fees.unroundedOrderFee(Quantity.ofContracts(1), Price.ofCents(10));
        Money paid = fees.orderFee(Quantity.ofContracts(1), Price.ofCents(10));

        assertThat(owed.micros()).isEqualTo(6_300);   // 0.63c
        assertThat(paid).isEqualTo(Money.ofCents(1)); // a full cent
        // This is why a positive edge is not enough; an opportunity needs a viable size.
        assertThat((paid.micros() - owed.micros()) * 100.0 / owed.micros())
                .isCloseTo(58.7, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    @DisplayName("the ceiling's bite shrinks as size grows")
    void ceilingMattersLessAtSize() {
        double smallOverhead = overheadPercent(1);
        double largeOverhead = overheadPercent(1000);

        assertThat(smallOverhead).isGreaterThan(50.0);
        assertThat(largeOverhead).isLessThan(0.1);
    }

    private double overheadPercent(long contracts) {
        Money owed = fees.unroundedOrderFee(Quantity.ofContracts(contracts), Price.ofCents(10));
        Money paid = fees.orderFee(Quantity.ofContracts(contracts), Price.ofCents(10));
        return (paid.micros() - owed.micros()) * 100.0 / owed.micros();
    }

    @Test
    @DisplayName("an exact cent is not rounded up further")
    void exactCentUnchanged() {
        assertThat(FeeModel.ceilToCent(10_000)).isEqualTo(10_000);
        assertThat(FeeModel.ceilToCent(20_000)).isEqualTo(20_000);
        assertThat(FeeModel.ceilToCent(1)).isEqualTo(10_000);
        assertThat(FeeModel.ceilToCent(9_999)).isEqualTo(10_000);
        assertThat(FeeModel.ceilToCent(10_001)).isEqualTo(20_000);
        assertThat(FeeModel.ceilToCent(0)).isZero();
    }

    @Test
    @DisplayName("the multiplier comes from the series, not from a constant")
    void multiplierFromSeries() {
        // KXHIGHNY publishes fee_type "quadratic", fee_multiplier "1".
        FeeModel weather = FeeModel.fromSeries("quadratic", "1");
        assertThat(weather.multiplier()).isEqualTo(1);
        assertThat(weather.feePerContract(Price.ofCents(50)).micros()).isEqualTo(17_500);

        // Crypto series carry a higher multiplier; the model must scale, not assume.
        FeeModel crypto = FeeModel.fromSeries("quadratic", "2");
        assertThat(crypto.feePerContract(Price.ofCents(50)).micros()).isEqualTo(35_000);
    }

    @Test
    @DisplayName("a non-quadratic fee type is rejected rather than mispriced")
    void rejectsUnknownFeeType() {
        assertThatThrownBy(() -> FeeModel.fromSeries("linear", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not 'quadratic'");
    }

    @Test
    @DisplayName("fractional contract counts are priced exactly")
    void fractionalContracts() {
        // Real levels hold 0.33 contracts, so a fill can be fractional.
        Money fee = fees.unroundedOrderFee(new Quantity(330_000L), Price.ofCents(50));
        // 0.07 * 0.33 * 0.5 * 0.5 = 0.005775 -> 5775 micros
        assertThat(fee.micros()).isEqualTo(5_775);
        assertThat(fees.orderFee(new Quantity(330_000L), Price.ofCents(50)))
                .isEqualTo(Money.ofCents(1));
    }

    @Test
    @DisplayName("a huge order does not overflow")
    void hugeOrderDoesNotOverflow() {
        Money fee = fees.orderFee(Quantity.ofContracts(1_000_000), Price.ofCents(50));
        // 0.07 * 1e6 * 0.25 = $17,500
        assertThat(fee.micros()).isEqualTo(17_500_000_000L);
    }
}
