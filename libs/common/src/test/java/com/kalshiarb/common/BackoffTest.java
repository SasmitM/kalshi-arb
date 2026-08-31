package com.kalshiarb.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.random.RandomGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BackoffTest {

    /** Returns a fixed draw, so the schedule is deterministic. */
    private static RandomGenerator constant(double value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public double nextDouble() {
                return value;
            }
        };
    }

    @Test
    @DisplayName("the cap doubles per attempt until it saturates at max")
    void capGrowsThenSaturates() {
        Backoff backoff = new Backoff(500, 30_000, 2.0, constant(0));

        assertThat(backoff.currentCapMillis()).isEqualTo(500);
        backoff.nextDelayMillis();
        assertThat(backoff.currentCapMillis()).isEqualTo(1_000);
        backoff.nextDelayMillis();
        assertThat(backoff.currentCapMillis()).isEqualTo(2_000);

        for (int i = 0; i < 20; i++) {
            backoff.nextDelayMillis();
        }
        assertThat(backoff.currentCapMillis()).isEqualTo(30_000);
    }

    @Test
    @DisplayName("equal jitter keeps the delay in [cap/2, cap] — never near zero")
    void equalJitterHasAFloor() {
        // Worst case for a full-jitter scheme: a draw of 0 would give a 0ms delay, and
        // hammering the server immediately after a rejection is the failure this avoids.
        Backoff atZero = new Backoff(1_000, 30_000, 2.0, constant(0.0));
        assertThat(atZero.nextDelayMillis()).isEqualTo(500);

        Backoff atOne = new Backoff(1_000, 30_000, 2.0, constant(0.999999));
        assertThat(atOne.nextDelayMillis()).isBetween(999L, 1_000L);
    }

    @Test
    @DisplayName("delays stay within bounds across a long outage")
    void delaysStayInBounds() {
        Backoff backoff = Backoff.forWebSocket(RandomGenerator.getDefault());

        for (int i = 0; i < 200; i++) {
            long cap = backoff.currentCapMillis();
            long delay = backoff.nextDelayMillis();
            assertThat(delay).isBetween(cap / 2, cap);
            assertThat(delay).isLessThanOrEqualTo(30_000);
        }
    }

    @Test
    @DisplayName("a huge attempt count does not overflow into a nonsense delay")
    void doesNotOverflow() {
        Backoff backoff = new Backoff(500, 30_000, 2.0, constant(0.5));

        for (int i = 0; i < 10_000; i++) {
            backoff.nextDelayMillis();
        }

        assertThat(backoff.currentCapMillis()).isEqualTo(30_000);
        assertThat(backoff.nextDelayMillis()).isBetween(15_000L, 30_000L);
        assertThat(backoff.attempts()).isEqualTo(10_001);
    }

    @Test
    @DisplayName("reset returns to the base delay so the next outage starts fast")
    void resetRestartsSchedule() {
        Backoff backoff = new Backoff(500, 30_000, 2.0, constant(0.0));
        for (int i = 0; i < 10; i++) {
            backoff.nextDelayMillis();
        }
        assertThat(backoff.currentCapMillis()).isEqualTo(30_000);

        backoff.reset();

        assertThat(backoff.attempts()).isZero();
        assertThat(backoff.currentCapMillis()).isEqualTo(500);
        assertThat(backoff.nextDelayMillis()).isEqualTo(250);
    }

    @Test
    @DisplayName("invalid configuration is rejected")
    void rejectsBadConfig() {
        RandomGenerator r = constant(0.5);
        assertThatThrownBy(() -> new Backoff(0, 1000, 2.0, r))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Backoff(1000, 500, 2.0, r))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Backoff(500, 1000, 0.5, r))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Backoff(500, 1000, 2.0, null))
                .isInstanceOf(NullPointerException.class);
    }
}
