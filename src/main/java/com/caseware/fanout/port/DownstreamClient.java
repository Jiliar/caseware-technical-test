package com.caseware.fanout.port;

import com.caseware.fanout.model.IdempotencyKey;
import java.time.Duration;
import java.util.Objects;

/**
 * The slow (~1 minute), capacity-limited service owned by another team, called once per target engagement.
 *
 * <ul>
 *   <li>Pre: the caller guarantees at most {@code C_max} concurrent calls.</li>
 *   <li>Post: returns within {@code timeout}, or returns {@code RetryableFailure(TIMEOUT)}.</li>
 *   <li>Errors are classified, never thrown: 4xx/validation is {@link NonRetryableFailure}; 5xx, saturation and
 *       timeout are {@link RetryableFailure}.</li>
 *   <li>Idempotent by {@link IdempotencyKey#value()}: repeating a call with the same key does not repeat the effect
 *       (a call that timed out after applying it answers {@link Success} next time).</li>
 * </ul>
 */
public interface DownstreamClient {

    Result call(IdempotencyKey key, Duration timeout);

    /** Outcome of one call; the worker handles every case in one exhaustive {@code switch}. */
    sealed interface Result {
    }

    record Success() implements Result {
    }

    record RetryableFailure(String error) implements Result {
        public static final String TIMEOUT = "TIMEOUT";

        public RetryableFailure {
            Objects.requireNonNull(error, "error");
        }
    }

    record NonRetryableFailure(String error) implements Result {
        public NonRetryableFailure {
            Objects.requireNonNull(error, "error");
        }
    }
}
