package com.caseware.fanout.worker;

import com.caseware.fanout.model.FanOutConfig;
import com.caseware.fanout.model.IdempotencyKey;
import com.caseware.fanout.model.RunSummary;
import com.caseware.fanout.port.Clock;
import com.caseware.fanout.port.DeadLetterSink;
import com.caseware.fanout.port.DeadLetterSink.DeadLetter;
import com.caseware.fanout.port.DeadLetterSink.Reason;
import com.caseware.fanout.port.DownstreamClient;
import com.caseware.fanout.port.DownstreamClient.NonRetryableFailure;
import com.caseware.fanout.port.DownstreamClient.RetryableFailure;
import com.caseware.fanout.port.DownstreamClient.Success;
import com.caseware.fanout.port.EngagementTargetSource;
import com.caseware.fanout.port.ProgressStore;
import com.caseware.fanout.port.ProgressStore.Lease;
import com.caseware.fanout.port.Sleeper;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fans one template publication out to its target engagements: one downstream call per engagement, never more
 * than {@code C_max} at a time.
 *
 * <p>Concurrency: one virtual thread per claimed task (blocking ~1 minute on I/O is cheap there). Virtual
 * threads do not limit themselves, so a {@link Semaphore} of {@code C_max} permits bounds the calls. The permit
 * is taken <em>before</em> the next target is claimed, so reading stops while capacity is full: read but
 * unfinished tasks never exceed {@code C_max + pageSize}, whatever the size of the publication. A task keeps its
 * permit while backing off, so the permit count is an exact bound on calls.
 *
 * <p>Failure and idempotency: at-least-once. Each task is claimed with a lease; if a worker dies, its leases
 * expire and a later run of the same publication reclaims those tasks, while {@code DONE} tasks are skipped.
 * Every call carries the {@link IdempotencyKey}, so repeats never duplicate the effect. Several instances may
 * run the same publication at once; the store's atomic claim keeps them apart.
 */
public final class FanOutWorker {

    private enum Outcome { DONE, DEAD_LETTERED, LEASE_LOST }

    private final String workerId;
    private final FanOutConfig config;
    private final EngagementTargetSource source;
    private final DownstreamClient downstream;
    private final ProgressStore store;
    private final DeadLetterSink deadLetters;
    private final ExponentialBackoffWithJitter backoff;
    private final Clock clock;
    private final Sleeper sleeper;

    /** {@code workerId} must be unique per running instance: it is the lease owner. */
    public FanOutWorker(String workerId, FanOutConfig config, EngagementTargetSource source,
                        DownstreamClient downstream, ProgressStore store, DeadLetterSink deadLetters,
                        ExponentialBackoffWithJitter backoff, Clock clock, Sleeper sleeper) {
        this.workerId = workerId;
        this.config = config;
        this.source = source;
        this.downstream = downstream;
        this.store = store;
        this.deadLetters = deadLetters;
        this.backoff = backoff;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * Processes every target of the publication that is neither finished nor leased by another live worker, and
     * returns once every task it started has ended. Safe to repeat: finished tasks are skipped.
     */
    public RunSummary run(String publishId, String targetVersion) throws InterruptedException {
        Semaphore permits = new Semaphore(config.maxConcurrency());
        AtomicInteger done = new AtomicInteger();
        AtomicInteger deadLettered = new AtomicInteger();
        int skipped = 0;
        // close() waits for every started task.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            String token = null;
            do {
                EngagementTargetSource.Page page = source.page(publishId, token, config.pageSize());
                for (String engagementId : page.engagementIds()) {
                    IdempotencyKey key = new IdempotencyKey(publishId, engagementId, targetVersion);
                    if (store.createIfAbsent(key).status().isTerminal()) {
                        skipped++;
                        continue;
                    }
                    permits.acquire();
                    Optional<Lease> lease = store.claim(key, workerId, clock.now(), config.leaseTtl());
                    if (lease.isEmpty()) {
                        // Finished meanwhile, a repeated id, or leased by a live worker.
                        permits.release();
                        skipped++;
                        continue;
                    }
                    executor.execute(() -> {
                        try {
                            switch (process(lease.get())) {
                                case DONE -> done.incrementAndGet();
                                case DEAD_LETTERED -> deadLettered.incrementAndGet();
                                case LEASE_LOST -> { }
                            }
                        } finally {
                            permits.release();
                        }
                    });
                }
                token = page.nextToken();
            } while (token != null);
        }
        return new RunSummary(done.get(), deadLettered.get(), skipped);
    }

    /** Drives one claimed task to {@code DONE} or the DLQ: call, retry with backoff, give up. */
    private Outcome process(Lease lease) {
        try {
            while (true) {
                int attempt = store.beginAttempt(lease, clock.now().plus(config.leaseTtl()));
                if (attempt < 0) {
                    return Outcome.LEASE_LOST;
                }
                // A reclaimed task may already have used every attempt (its previous holder died mid-call).
                if (attempt > config.maxAttempts()) {
                    return deadLetter(lease, Reason.RETRIES_EXHAUSTED, attempt - 1,
                            "attempts exhausted by a previous holder");
                }
                switch (downstream.call(lease.key(), config.callTimeout())) {
                    case Success() -> {
                        // false: the lease was reclaimed; the new holder repeats the call with the same key.
                        return store.complete(lease) ? Outcome.DONE : Outcome.LEASE_LOST;
                    }
                    case NonRetryableFailure(String error) -> {
                        return deadLetter(lease, Reason.NON_RETRYABLE, attempt, error);
                    }
                    case RetryableFailure(String error) -> {
                        if (attempt >= config.maxAttempts()) {
                            return deadLetter(lease, Reason.RETRIES_EXHAUSTED, attempt, error);
                        }
                        sleeper.sleep(backoff.delay(attempt));
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.LEASE_LOST;
        }
    }

    /** Sends before marking FAILED: a crash in between causes a resend (the sink deduplicates), never a loss. */
    private Outcome deadLetter(Lease lease, Reason reason, int attempts, String error) {
        deadLetters.send(new DeadLetter(lease.key(), reason, attempts, error, clock.now()));
        return store.fail(lease) ? Outcome.DEAD_LETTERED : Outcome.LEASE_LOST;
    }
}
