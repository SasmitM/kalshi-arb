package com.kalshiarb.book;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.Side;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three rules that make a book silently rot. Every failure here produces a book that looks
 * plausible and is wrong, which the detector would then read as a constraint violation.
 */
class DeltaApplierTest {

    private static final MarketTicker TICKER = MarketTicker.of("KXHIGHNY-26SEP01-T83");
    private static final long P45 = 450_000L;
    private static final long ONE_CONTRACT = 1_000_000L;

    private NavigableMap<Long, Long> side;

    @BeforeEach
    void setUp() {
        side = new TreeMap<>();
    }

    private long apply(long price, long delta) {
        return DeltaApplier.apply(side, price, delta, TICKER, Side.YES);
    }

    @Test
    @DisplayName("RULE 1: a delta ADDS to the existing level, it is not an assignment")
    void deltaAccumulates() {
        apply(P45, 100 * ONE_CONTRACT);
        apply(P45, 50 * ONE_CONTRACT);

        // Assignment semantics would leave 50 here. That bug tracks reality for a while and
        // then diverges with no error at all.
        assertThat(side.get(P45)).isEqualTo(150 * ONE_CONTRACT);
    }

    @Test
    @DisplayName("a level absent from the map starts at zero")
    void firstDeltaCreatesLevel() {
        assertThat(apply(P45, 7 * ONE_CONTRACT)).isEqualTo(7 * ONE_CONTRACT);
        assertThat(side).containsExactly(java.util.Map.entry(P45, 7 * ONE_CONTRACT));
    }

    @Test
    @DisplayName("negative deltas subtract; a cancel, fill and expiry are indistinguishable")
    void negativeDeltaSubtracts() {
        apply(P45, 100 * ONE_CONTRACT);

        assertThat(apply(P45, -40 * ONE_CONTRACT)).isEqualTo(60 * ONE_CONTRACT);
        assertThat(side.get(P45)).isEqualTo(60 * ONE_CONTRACT);
    }

    @Test
    @DisplayName("RULE 2: a level reaching exactly zero is REMOVED, not stored as zero")
    void zeroLevelIsRemoved() {
        apply(P45, 100 * ONE_CONTRACT);
        assertThat(apply(P45, -100 * ONE_CONTRACT)).isZero();

        // A retained zero would still win lastEntry(), so the book would quote a best bid at
        // which nothing is available.
        assertThat(side).doesNotContainKey(P45);
        assertThat(side).isEmpty();
    }

    @Test
    @DisplayName("RULE 3: a level going negative THROWS and never clamps")
    void negativeLevelThrows() {
        apply(P45, 100 * ONE_CONTRACT);

        assertThatThrownBy(() -> apply(P45, -100 * ONE_CONTRACT - 1))
                .isInstanceOf(DeltaApplier.NegativeLevelException.class)
                .hasMessageContaining("negative book level")
                .hasMessageContaining("KXHIGHNY-26SEP01-T83")
                .hasMessageContaining("$0.4500")
                .hasMessageContaining("sequencing bug")
                .hasMessageContaining("never clamp");
    }

    @Test
    @DisplayName("a throw leaves the level untouched rather than half-applied")
    void throwLeavesStateIntact() {
        apply(P45, 100 * ONE_CONTRACT);

        assertThatThrownBy(() -> apply(P45, -200 * ONE_CONTRACT))
                .isInstanceOf(DeltaApplier.NegativeLevelException.class);

        // Partial application would be worse than either succeeding or failing cleanly.
        assertThat(side.get(P45)).isEqualTo(100 * ONE_CONTRACT);
    }

    @Test
    @DisplayName("a delta against a level that does not exist throws, exposing the real cause")
    void negativeAgainstMissingLevelThrows() {
        // The signature of a gap: a cancel arrives for a level whose creation we never saw.
        assertThatThrownBy(() -> apply(P45, -ONE_CONTRACT))
                .isInstanceOf(DeltaApplier.NegativeLevelException.class);
    }

    @Test
    @DisplayName("the exception carries the diagnostic fields, not just a message")
    void exceptionCarriesContext() {
        apply(P45, 5 * ONE_CONTRACT);

        assertThatThrownBy(() -> apply(P45, -6 * ONE_CONTRACT))
                .isInstanceOfSatisfying(DeltaApplier.NegativeLevelException.class, e -> {
                    assertThat(e.ticker()).isEqualTo(TICKER);
                    assertThat(e.side()).isEqualTo(Side.YES);
                    assertThat(e.priceMicros()).isEqualTo(P45);
                    assertThat(e.existingMicroContracts()).isEqualTo(5 * ONE_CONTRACT);
                    assertThat(e.deltaMicroContracts()).isEqualTo(-6 * ONE_CONTRACT);
                });
    }

    @Test
    @DisplayName("fractional deltas are exact — real Kalshi deltas include 0.33 contracts")
    void fractionalDeltasAreExact() {
        // Straight from a live frame: delta_fp "0.33".
        apply(P45, 330_000L);
        apply(P45, 330_000L);
        apply(P45, 330_000L);

        assertThat(side.get(P45)).isEqualTo(990_000L);

        // And back to exactly zero, with no rounding residue left behind.
        apply(P45, -990_000L);
        assertThat(side).isEmpty();
    }

    @Test
    @DisplayName("a zero delta is a no-op on an existing level and creates nothing on an absent one")
    void zeroDelta() {
        apply(P45, 10 * ONE_CONTRACT);
        assertThat(apply(P45, 0)).isEqualTo(10 * ONE_CONTRACT);
        assertThat(side).hasSize(1);

        assertThat(apply(999_000L, 0)).isZero();
        assertThat(side).hasSize(1);
    }

    @Test
    @DisplayName("levels are independent: a change at one price leaves others alone")
    void levelsAreIndependent() {
        apply(400_000L, 10 * ONE_CONTRACT);
        apply(P45, 20 * ONE_CONTRACT);
        apply(500_000L, 30 * ONE_CONTRACT);

        apply(P45, -20 * ONE_CONTRACT);

        assertThat(side).containsExactly(
                java.util.Map.entry(400_000L, 10 * ONE_CONTRACT),
                java.util.Map.entry(500_000L, 30 * ONE_CONTRACT));
    }
}
