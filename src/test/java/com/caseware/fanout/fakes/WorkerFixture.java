package com.caseware.fanout.fakes;

import com.caseware.fanout.inmemory.InMemoryProgressStore;
import com.caseware.fanout.model.FanOutConfig;
import com.caseware.fanout.model.IdempotencyKey;
import com.caseware.fanout.model.RunSummary;
import com.caseware.fanout.port.DeadLetterSink.DeadLetter;
import com.caseware.fanout.port.EngagementTargetSource;
import com.caseware.fanout.port.ProgressStore.Status;
import com.caseware.fanout.port.ProgressStore.TaskRecord;
import com.caseware.fanout.worker.ExponentialBackoffWithJitter;
import com.caseware.fanout.worker.FanOutWorker;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * One publication ({@code publish-1}, version {@code v2}) with {@code tasks} targets {@code eng-0 ..} wired to
 * fresh fakes. Shared by the JUnit tests and the plain-JDK validation, so it uses no test library.
 * {@link #SAFETY_TIMEOUT} only stops a hung scenario; nothing waits for it on the expected path.
 */
public final class WorkerFixture {

    public static final Duration SAFETY_TIMEOUT = Duration.ofSeconds(60);
    public static final String PUBLISH_ID = "publish-1";
    public static final String TARGET_VERSION = "v2";
    private static final long SEED = 42;

    public final FanOutConfig config;
    public final FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
    public final FakeDownstreamClient downstream = new FakeDownstreamClient();
    public final InMemoryProgressStore store = new InMemoryProgressStore();
    public final EngagementTargetSource source;
    private final List<String> engagementIds;
    private final Map<IdempotencyKey, DeadLetter> deadLetters = new ConcurrentHashMap<>();

    public WorkerFixture(FanOutConfig config, int tasks) {
        this.config = config;
        this.engagementIds = IntStream.range(0, tasks).mapToObj(i -> "eng-" + i).toList();
        // The page token is the offset of the next page.
        this.source = (publishId, token, limit) -> {
            int from = token == null ? 0 : Integer.parseInt(token);
            int to = Math.min(engagementIds.size(), from + limit);
            return new EngagementTargetSource.Page(engagementIds.subList(from, to),
                    to < engagementIds.size() ? Integer.toString(to) : null);
        };
    }

    /** Example values (configurable); what matters is their relation, which {@link FanOutConfig} validates. */
    public static FanOutConfig exampleConfig(int maxConcurrency, int maxRetries, int pageSize) {
        return new FanOutConfig(maxConcurrency, maxRetries, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30),
                Duration.ofMinutes(2), Duration.ofMinutes(5), pageSize);
    }

    public FanOutWorker worker(String workerId) {
        return worker(workerId, source);
    }

    public FanOutWorker worker(String workerId, EngagementTargetSource targets) {
        // The DLQ deduplicates by key, as its contract requires.
        return new FanOutWorker(workerId, config, targets, downstream, store,
                letter -> deadLetters.putIfAbsent(letter.key(), letter),
                new ExponentialBackoffWithJitter(config, new Random(SEED)), clock, clock);
    }

    public RunSummary run(FanOutWorker worker) throws Exception {
        return await(async(() -> worker.run(PUBLISH_ID, TARGET_VERSION)));
    }

    /** Runs while jumping the fake clock over every backoff sleep, so retries need no real waiting. */
    public RunSummary runDrivingClock(FanOutWorker worker) throws Exception {
        CompletableFuture<RunSummary> run = async(() -> worker.run(PUBLISH_ID, TARGET_VERSION));
        clock.runUntilDone(run, SAFETY_TIMEOUT);
        return await(run);
    }

    public List<DeadLetter> deadLetters() {
        return List.copyOf(deadLetters.values());
    }

    public IdempotencyKey key(int index) {
        return new IdempotencyKey(PUBLISH_ID, engagementIds.get(index), TARGET_VERSION);
    }

    public List<IdempotencyKey> allKeys() {
        return IntStream.range(0, engagementIds.size()).mapToObj(this::key).toList();
    }

    public Status status(IdempotencyKey key) {
        return store.get(key).map(TaskRecord::status).orElse(null);
    }

    public Set<IdempotencyKey> keysWithStatus(Status status) {
        return allKeys().stream().filter(k -> status(k) == status).collect(Collectors.toSet());
    }

    public static <T> CompletableFuture<T> async(Callable<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                future.complete(work.call());
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(SAFETY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }
}
