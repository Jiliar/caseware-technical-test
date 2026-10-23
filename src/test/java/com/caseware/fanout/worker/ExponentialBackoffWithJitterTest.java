package com.caseware.fanout.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.fakes.WorkerFixture;
import com.caseware.fanout.model.FanOutConfig;
import java.time.Duration;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ExponentialBackoffWithJitterTest {

    // Example values: base 1 s, factor 2, cap 30 s.
    private static final FanOutConfig CONFIG = WorkerFixture.exampleConfig(1, 8, 1);

    @Test
    void shouldGrowCeilingExponentiallyUpToTheCap_whenAttemptsIncrease() {
        var backoff = new ExponentialBackoffWithJitter(CONFIG, new Random(42));

        assertThat(IntStream.rangeClosed(1, 8).mapToObj(backoff::ceiling)).containsExactly(
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8),
                Duration.ofSeconds(16), Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30));
    }

    @Test
    void shouldKeepEveryDelayWithinZeroAndTheCeiling_whenSampledManyTimes() {
        var backoff = new ExponentialBackoffWithJitter(CONFIG, new Random(42));

        for (int attempt = 1; attempt <= 8; attempt++) {
            Duration ceiling = backoff.ceiling(attempt);
            for (int i = 0; i < 1_000; i++) {
                assertThat(backoff.delay(attempt)).isBetween(Duration.ZERO, ceiling);
            }
        }
    }
}
