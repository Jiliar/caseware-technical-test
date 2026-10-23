package com.caseware.fanout.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.fakes.FakeDownstreamClient.Outcome;
import com.caseware.fanout.fakes.WorkerFixture;
import com.caseware.fanout.model.FanOutConfig;
import com.caseware.fanout.model.IdempotencyKey;
import com.caseware.fanout.model.RunSummary;
import com.caseware.fanout.port.DeadLetterSink.DeadLetter;
import com.caseware.fanout.port.DeadLetterSink.Reason;
import com.caseware.fanout.port.ProgressStore.Status;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DeadLetterTest {

    @Test
    void shouldSendTaskToDeadLetterQueue_whenRetriesAreExhausted() throws Exception {
        FanOutConfig config = WorkerFixture.exampleConfig(4, 3, 10);
        WorkerFixture f = new WorkerFixture(config, 1);
        f.downstream.alwaysAnswer(Outcome.RETRYABLE);

        RunSummary summary = f.runDrivingClock(f.worker("worker-1"));

        IdempotencyKey key = f.key(0);
        assertThat(f.downstream.calls(key)).isEqualTo(config.maxAttempts());
        assertThat(f.status(key)).isEqualTo(Status.FAILED);
        assertThat(summary.deadLettered()).isEqualTo(1);
        assertThat(f.deadLetters()).singleElement().satisfies(letter -> {
            assertThat(letter.reason()).isEqualTo(Reason.RETRIES_EXHAUSTED);
            assertThat(letter.attempts()).isEqualTo(config.maxAttempts());
        });
        // One backoff between consecutive attempts, each within [0, ceiling(attempt)].
        ExponentialBackoffWithJitter backoff = new ExponentialBackoffWithJitter(config, new Random());
        List<Duration> delays = f.clock.sleeps();
        assertThat(delays).hasSize(config.maxRetries());
        for (int attempt = 1; attempt <= delays.size(); attempt++) {
            assertThat(delays.get(attempt - 1)).isBetween(Duration.ZERO, backoff.ceiling(attempt));
        }
    }

    @Test
    void shouldSendTaskStraightToDeadLetterQueue_whenTheErrorIsNotRetryable() throws Exception {
        WorkerFixture f = new WorkerFixture(WorkerFixture.exampleConfig(4, 3, 10), 1);
        f.downstream.alwaysAnswer(Outcome.NON_RETRYABLE);

        f.runDrivingClock(f.worker("worker-1"));

        IdempotencyKey key = f.key(0);
        assertThat(f.downstream.calls(key)).isEqualTo(1);
        assertThat(f.clock.sleeps()).isEmpty();
        assertThat(f.status(key)).isEqualTo(Status.FAILED);
        assertThat(f.deadLetters()).extracting(DeadLetter::reason).containsExactly(Reason.NON_RETRYABLE);
    }
}
