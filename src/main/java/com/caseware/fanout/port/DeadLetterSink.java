package com.caseware.fanout.port;

import com.caseware.fanout.model.IdempotencyKey;
import java.time.Instant;

/**
 * Receives tasks that exhausted their retries or failed with a non-retryable error.
 *
 * <p>Idempotent by {@code letter.key()}: the worker sends before marking the task {@code FAILED}, so a crash in
 * between leads to a resend, never to a lost letter. A failure is thrown; the task then stays
 * {@code IN_PROGRESS} and is retried by a later run once its lease expires.
 */
public interface DeadLetterSink {

    void send(DeadLetter letter);

    enum Reason {
        RETRIES_EXHAUSTED, NON_RETRYABLE
    }

    record DeadLetter(IdempotencyKey key, Reason reason, int attempts, String lastError, Instant failedAt) {
    }
}
