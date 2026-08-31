package com.kalshiarb.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    @DisplayName("parses the balance_dollars field from a real /portfolio/balance response")
    void parsesBalance() {
        // {"balance": 1000, "balance_dollars": "10.0000"} — the string field is authoritative.
        Money balance = Money.fromFixedPointString("10.0000");
        assertThat(balance.micros()).isEqualTo(10_000_000L);
        assertThat(balance.toDollarString()).isEqualTo("10.0000");
    }

    @Test
    @DisplayName("money is signed, unlike Price — a losing opportunity has negative edge")
    void supportsNegative() {
        // The CLAUDE.md worked example: 4c of apparent edge against 5.21c of fees.
        Money grossEdge = Money.ofCents(4);
        Money fees = Money.fromFixedPointString("0.0521");
        Money net = grossEdge.minus(fees);

        assertThat(net.isNegative()).isTrue();
        assertThat(net.micros()).isEqualTo(-12_100L);
        assertThat(net.toString()).isEqualTo("-$0.0121");
    }

    @Test
    @DisplayName("arithmetic is exact")
    void arithmetic() {
        Money a = Money.ofCents(30);
        Money b = Money.ofCents(25);
        assertThat(a.plus(b)).isEqualTo(Money.ofCents(55));
        assertThat(b.minus(a)).isEqualTo(Money.ofCents(-5));
        assertThat(a.negated().negated()).isEqualTo(a);
        assertThat(Money.ZERO.minus(a)).isEqualTo(a.negated());
    }

    @Test
    @DisplayName("converts from a Price without losing the scale")
    void fromPrice() {
        assertThat(Money.of(Price.fromFixedPointString("0.0800")))
                .isEqualTo(Money.ofCents(8));
    }
}
