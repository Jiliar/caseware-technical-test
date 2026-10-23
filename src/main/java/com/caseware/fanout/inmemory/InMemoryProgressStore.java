package com.caseware.fanout.inmemory;

import com.caseware.fanout.model.IdempotencyKey;
import com.caseware.fanout.port.ProgressStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * {@link ProgressStore} backed by a {@link ConcurrentHashMap}. Every transition runs inside
 * {@code computeIfPresent}, which is atomic per key: the condition check and the write cannot interleave with
 * another writer of the same key, the same guarantee as a DynamoDB conditional write.
 */
public final class InMemoryProgressStore implements ProgressStore {

    private final ConcurrentHashMap<IdempotencyKey, TaskRecord> records = new ConcurrentHashMap<>();

    @Override
    public TaskRecord createIfAbsent(IdempotencyKey key) {
        return records.computeIfAbsent(key, k -> new TaskRecord(k, Status.PENDING, 0, null, 0));
    }

    @Override
    public Optional<Lease> claim(IdempotencyKey key, String owner, Instant now, Duration leaseTtl) {
        AtomicReference<Lease> won = new AtomicReference<>();
        records.computeIfPresent(key, (k, current) -> {
            if (!current.isClaimableAt(now)) {
                return current;
            }
            Lease lease = new Lease(k, owner, now.plus(leaseTtl), current.version() + 1);
            won.set(lease);
            return new TaskRecord(k, Status.IN_PROGRESS, current.attempts(), lease, lease.version());
        });
        return Optional.ofNullable(won.get());
    }

    @Override
    public int beginAttempt(Lease lease, Instant newUntil) {
        TaskRecord updated = updateOwned(lease, current -> new TaskRecord(lease.key(), Status.IN_PROGRESS,
                current.attempts() + 1, new Lease(lease.key(), lease.owner(), newUntil, lease.version()),
                current.version()));
        return updated == null ? -1 : updated.attempts();
    }

    @Override
    public boolean complete(Lease lease) {
        return finish(lease, Status.DONE);
    }

    @Override
    public boolean fail(Lease lease) {
        return finish(lease, Status.FAILED);
    }

    @Override
    public Optional<TaskRecord> get(IdempotencyKey key) {
        return Optional.ofNullable(records.get(key));
    }

    private boolean finish(Lease lease, Status terminal) {
        return updateOwned(lease, current ->
                new TaskRecord(lease.key(), terminal, current.attempts(), null, current.version() + 1)) != null;
    }

    /** Applies {@code change} atomically while {@code lease} still owns the record; returns the new record or null. */
    private TaskRecord updateOwned(Lease lease, UnaryOperator<TaskRecord> change) {
        AtomicReference<TaskRecord> applied = new AtomicReference<>();
        records.computeIfPresent(lease.key(), (k, current) -> {
            if (!current.isOwnedBy(lease)) {
                return current;
            }
            applied.set(change.apply(current));
            return applied.get();
        });
        return applied.get();
    }
}
