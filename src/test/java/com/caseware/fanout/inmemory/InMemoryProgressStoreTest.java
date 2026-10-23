package com.caseware.fanout.inmemory;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.model.IdempotencyKey;
import com.caseware.fanout.port.ProgressStore.Lease;
import com.caseware.fanout.port.ProgressStore.Status;
import com.caseware.fanout.port.ProgressStore.TaskRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class InMemoryProgressStoreTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Instant AFTER_EXPIRY = NOW.plus(TTL).plusSeconds(1);

    private final InMemoryProgressStore store = new InMemoryProgressStore();
    private final IdempotencyKey key = new IdempotencyKey("publish-1", "eng-0", "v2");

    @Test
    void shouldGrantExactlyOneLease_whenTenWorkersClaimTheSameTaskAtOnce() throws Exception {
        store.createIfAbsent(key);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Lease> won = new ConcurrentLinkedQueue<>();
        List<Thread> contenders = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String owner = "worker-" + i;
            contenders.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    store.claim(key, owner, NOW, TTL).ifPresent(won::add);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Thread contender : contenders) {
            contender.join();
        }

        assertThat(won).hasSize(1);
        assertThat(store.get(key)).map(TaskRecord::status).contains(Status.IN_PROGRESS);
    }

    @Test
    void shouldRefuseTheClaim_whenAnotherWorkerHoldsALiveLease() {
        claimFresh("worker-1");

        assertThat(store.claim(key, "worker-2", NOW.plusSeconds(1), TTL)).isEmpty();
    }

    @Test
    void shouldRejectTheOldHolder_whenAnExpiredLeaseIsReclaimed() {
        Lease stale = claimFresh("worker-1");

        Optional<Lease> reclaimed = store.claim(key, "worker-2", AFTER_EXPIRY, TTL);

        assertThat(reclaimed).isPresent();
        assertThat(store.beginAttempt(stale, AFTER_EXPIRY.plus(TTL))).isEqualTo(-1);
        assertThat(store.complete(stale)).isFalse();
        assertThat(store.fail(stale)).isFalse();
        assertThat(store.complete(reclaimed.get())).isTrue();
    }

    @Test
    void shouldCountAttemptsAcrossOwners_whenATaskIsReclaimed() {
        Lease first = claimFresh("worker-1");
        assertThat(store.beginAttempt(first, NOW.plus(TTL))).isEqualTo(1);

        Lease second = store.claim(key, "worker-2", NOW.plus(TTL).plusSeconds(1), TTL).orElseThrow();

        assertThat(store.beginAttempt(second, AFTER_EXPIRY.plus(TTL))).isEqualTo(2);
    }

    @Test
    void shouldNeverClaimAgain_whenTheTaskIsDoneOrFailed() {
        assertThat(store.complete(claimFresh("worker-1"))).isTrue();
        IdempotencyKey other = new IdempotencyKey("publish-1", "eng-1", "v2");
        store.createIfAbsent(other);
        Lease otherLease = store.claim(other, "worker-1", NOW, TTL).orElseThrow();
        assertThat(store.fail(otherLease)).isTrue();

        Instant farFuture = NOW.plus(TTL.multipliedBy(100));
        assertThat(store.claim(key, "worker-2", farFuture, TTL)).isEmpty();
        assertThat(store.claim(other, "worker-2", farFuture, TTL)).isEmpty();
        assertThat(store.get(other).orElseThrow().status()).isEqualTo(Status.FAILED);
    }

    private Lease claimFresh(String owner) {
        store.createIfAbsent(key);
        return store.claim(key, owner, NOW, TTL).orElseThrow();
    }
}
