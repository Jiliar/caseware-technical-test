package com.caseware.fanout.port;

import com.caseware.fanout.model.IdempotencyKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-task progress shared by every worker instance; the single source of truth for recovery.
 *
 * <p>Lifecycle: {@code PENDING -> IN_PROGRESS -> DONE | FAILED}. An {@code IN_PROGRESS} task whose lease has
 * expired may be claimed again; {@code DONE} and {@code FAILED} are terminal.
 *
 * <p>Atomicity: every method is atomic per key (compare-and-set). A condition that does not hold returns
 * {@code false}, {@code -1} or empty, never an exception. Methods taking a {@link Lease} act only while the record
 * still has that lease's owner and version, so a worker whose lease was reclaimed can no longer change the task.
 * In production this maps to conditional writes (on status, lease owner and version).
 */
public interface ProgressStore {

    /** Creates a {@code PENDING} record if none exists; otherwise returns the existing one untouched. */
    TaskRecord createIfAbsent(IdempotencyKey key);

    /** {@code PENDING}, or {@code IN_PROGRESS} with a lease expired before {@code now}, becomes ours. Else empty. */
    Optional<Lease> claim(IdempotencyKey key, String owner, Instant now, Duration leaseTtl);

    /**
     * Counts an attempt <em>before</em> the call (so a crash mid-call counts too) and renews the lease.
     *
     * @return the attempt number, starting at 1, or {@code -1} if the lease is no longer held
     */
    int beginAttempt(Lease lease, Instant newUntil);

    /** {@code IN_PROGRESS -> DONE}; {@code false} if the lease is no longer held. */
    boolean complete(Lease lease);

    /** {@code IN_PROGRESS -> FAILED}; {@code false} if the lease is no longer held. */
    boolean fail(Lease lease);

    Optional<TaskRecord> get(IdempotencyKey key);

    enum Status {
        PENDING, IN_PROGRESS, DONE, FAILED;

        public boolean isTerminal() {
            return this == DONE || this == FAILED;
        }
    }

    /** Proof of ownership: {@code version} is the record version written by the claim. */
    record Lease(IdempotencyKey key, String owner, Instant until, long version) {
    }

    /**
     * @param attempts attempts started so far, across all lease holders
     * @param lease    current lease; {@code null} unless {@code IN_PROGRESS}
     * @param version  incremented by every claim and every terminal transition
     */
    record TaskRecord(IdempotencyKey key, Status status, int attempts, Lease lease, long version) {
        public TaskRecord {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(status, "status");
            if ((status == Status.IN_PROGRESS) != (lease != null)) {
                throw new IllegalArgumentException("a lease exists exactly while the task is IN_PROGRESS");
            }
        }

        public boolean isClaimableAt(Instant now) {
            return status == Status.PENDING || (status == Status.IN_PROGRESS && lease.until().isBefore(now));
        }

        public boolean isOwnedBy(Lease candidate) {
            return status == Status.IN_PROGRESS && lease.owner().equals(candidate.owner())
                    && version == candidate.version();
        }
    }
}
