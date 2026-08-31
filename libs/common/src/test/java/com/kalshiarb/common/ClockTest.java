package com.kalshiarb.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClockTest {

    @Test
    @DisplayName("a fixed clock does not move, which is what makes signing tests deterministic")
    void fixedClockIsFrozen() {
        Clock clock = Clock.fixed(1_756_658_400_000L);

        assertThat(clock.currentTimeMillis()).isEqualTo(1_756_658_400_000L);
        assertThat(clock.currentTimeMillis()).isEqualTo(clock.currentTimeMillis());
        assertThat(clock.instant()).isEqualTo(Instant.parse("2025-08-31T16:40:00Z"));
    }

    @Test
    @DisplayName("the system clock reports a plausible wall time and a monotonic nano time")
    void systemClockAdvances() {
        Clock clock = Clock.system();

        // Sanity band rather than an exact value: after 2020, before 2100.
        assertThat(clock.currentTimeMillis()).isGreaterThan(1_577_836_800_000L);
        assertThat(clock.currentTimeMillis()).isLessThan(4_102_444_800_000L);

        long first = clock.nanoTime();
        long second = clock.nanoTime();
        assertThat(second).isGreaterThanOrEqualTo(first);
    }

    @Test
    @DisplayName("Clock.system() is the single shared instance")
    void systemClockIsSingleton() {
        assertThat(Clock.system()).isSameAs(Clock.system());
    }
}
