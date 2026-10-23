package com.caseware.fanout.validation;

import com.caseware.fanout.fakes.FakeDownstreamClient.Outcome;
import com.caseware.fanout.fakes.WorkerFixture;
import com.caseware.fanout.inmemory.InMemoryProgressStore;
import com.caseware.fanout.model.FanOutConfig;
import com.caseware.fanout.model.IdempotencyKey;
import com.caseware.fanout.model.RunSummary;
import com.caseware.fanout.port.DeadLetterSink.DeadLetter;
import com.caseware.fanout.port.DeadLetterSink.Reason;
import com.caseware.fanout.port.EngagementTargetSource;
import com.caseware.fanout.port.ProgressStore.Lease;
import com.caseware.fanout.port.ProgressStore.Status;
import com.caseware.fanout.port.ProgressStore.TaskRecord;
import com.caseware.fanout.worker.ExponentialBackoffWithJitter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Checks the worker against the take-home themes (concurrency, failure, idempotency, downstream capacity) with
 * the plain JDK: no build tool, no test library.
 *
 * <p>Each scenario runs the real worker against the fakes and prints its INPUT, the OUTPUT it observed, what was
 * EXPECTED and the RESULT. The process exits with 0 only if every scenario passes. Nothing waits in real time:
 * time is a fake clock, and overlapping calls are forced by holding them in the fake downstream. Every
 * configuration value is an example (configurable), not a real capacity.
 */
public final class LogicValidation {

    private static final String CONCURRENCY = "CONCURRENCY";
    private static final String FAILURE = "FAILURE";
    private static final String IDEMPOTENCY = "IDEMPOTENCY";
    private static final String CAPACITY = "DOWNSTREAM CAPACITY";

    private final List<String> table = new ArrayList<>();
    private int failures;

    public static void main(String[] args) {
        LogicValidation v = new LogicValidation();
        long started = System.nanoTime();
        v.run(1, "Concurrency limit with a large publish", List.of(CONCURRENCY, CAPACITY),
                LogicValidation::concurrencyLimit);
        v.run(2, "Large publish: bounded reading", List.of(CONCURRENCY, CAPACITY), LogicValidation::boundedReading);
        v.run(3, "Retryable errors, then success", List.of(FAILURE, CAPACITY), LogicValidation::retryThenSuccess);
        v.run(4, "Retries exhausted and non-retryable errors go to the DLQ", List.of(FAILURE),
                LogicValidation::deadLetters);
        v.run(5, "Retry, republish and new target version", List.of(IDEMPOTENCY), LogicValidation::idempotency);
        v.run(6, "Worker crash: resume after lease expiry", List.of(FAILURE, IDEMPOTENCY),
                LogicValidation::crashRecovery);
        v.run(7, "ProgressStore contract: atomic claim, stale lease rejected", List.of(CONCURRENCY, FAILURE),
                LogicValidation::storeAtomicity);
        long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        System.out.println("SUMMARY");
        v.table.forEach(System.out::println);
        System.out.printf("%d scenarios, %d failed, %d ms%n", v.table.size(), v.failures, millis);
        System.exit(v.failures == 0 ? 0 : 1);
    }

    /** The fake holds the first C_max calls until they overlap, so the limit is reached and any excess is seen. */
    private static boolean concurrencyLimit(Report r) throws Exception {
        int tasks = 40_000;
        int maxConcurrency = 50;
        WorkerFixture f = new WorkerFixture(WorkerFixture.exampleConfig(maxConcurrency, 3, 100), tasks);
        r.input(describe(f, tasks) + ", every call succeeds");
        f.downstream.holdUntilConcurrent(maxConcurrency);

        RunSummary summary = f.run(f.worker("worker-1"));

        int peak = f.downstream.maxConcurrent();
        int done = f.keysWithStatus(Status.DONE).size();
        r.output("peak simultaneous downstream calls=" + peak + ", total calls=" + f.downstream.totalCalls());
        r.output("tasks DONE in store=" + done + ", " + summary);
        r.expected("peak <= C_max (" + maxConcurrency + ") and reached; all " + tasks + " tasks DONE, one call each");
        return peak == maxConcurrency && done == tasks && f.downstream.totalCalls() == tasks && summary.done() == tasks;
    }

    /** While the downstream is blocked the worker must stop reading; afterwards it reads only as tasks finish. */
    private static boolean boundedReading(Report r) throws Exception {
        int tasks = 500;
        int maxConcurrency = 8;
        int pageSize = 10;
        WorkerFixture f = new WorkerFixture(WorkerFixture.exampleConfig(maxConcurrency, 3, pageSize), tasks);
        r.input(describe(f, tasks) + ", downstream blocked until the reader has stopped");
        AtomicInteger read = new AtomicInteger();
        List<Integer> unfinishedAtRequest = new ArrayList<>();
        EngagementTargetSource recording = (publishId, token, limit) -> {
            unfinishedAtRequest.add(read.get() - f.downstream.completedCalls());
            EngagementTargetSource.Page page = f.source.page(publishId, token, limit);
            read.addAndGet(page.engagementIds().size());
            return page;
        };
        f.downstream.holdCallsAfter(0);
        CompletableFuture<RunSummary> run = WorkerFixture.async(() -> f.run(f.worker("worker-1", recording)));
        f.downstream.awaitHeld(maxConcurrency, WorkerFixture.SAFETY_TIMEOUT);
        int readWhileBlocked = read.get();
        int pagesWhileBlocked = unfinishedAtRequest.size();
        f.downstream.releaseHeld();
        RunSummary summary = WorkerFixture.await(run);

        int maxUnfinished = unfinishedAtRequest.stream().mapToInt(Integer::intValue).max().orElse(0);
        r.output("while blocked: pages requested=" + pagesWhileBlocked + ", targets read=" + readWhileBlocked
                + ", calls in flight=" + maxConcurrency);
        r.output("max read-but-unfinished when asking for a page=" + maxUnfinished + ", page requests="
                + unfinishedAtRequest.size() + ", " + summary);
        r.expected("read-but-unfinished never above C_max + pageSize (" + (maxConcurrency + pageSize)
                + "); at a page request never above C_max (" + maxConcurrency + "); all DONE");
        return readWhileBlocked <= maxConcurrency + pageSize && maxUnfinished <= maxConcurrency
                && summary.done() == tasks;
    }

    private static boolean retryThenSuccess(Report r) throws Exception {
        FanOutConfig config = WorkerFixture.exampleConfig(4, 4, 10);
        WorkerFixture f = new WorkerFixture(config, 1);
        IdempotencyKey key = f.key(0);
        f.downstream.script(key, Outcome.RETRYABLE, Outcome.RETRYABLE, Outcome.RETRYABLE, Outcome.SUCCESS);
        r.input(describe(f, 1) + ", backoff base=" + config.backoffBase().toMillis() + " ms, factor="
                + config.backoffFactor() + ", cap=" + config.backoffCap().toMillis() + " ms, full jitter");
        r.input("injected: 3 retryable failures (503), then success");

        f.runDrivingClock(f.worker("worker-1"));

        ExponentialBackoffWithJitter backoff = new ExponentialBackoffWithJitter(config, new Random());
        List<Duration> delays = f.clock.sleeps();
        boolean withinBounds = true;
        for (int n = 1; n <= delays.size(); n++) {
            Duration ceiling = backoff.ceiling(n);
            withinBounds &= !delays.get(n - 1).isNegative() && delays.get(n - 1).compareTo(ceiling) <= 0;
            r.output("backoff after attempt " + n + ": " + delays.get(n - 1).toMillis() + " ms (ceiling "
                    + ceiling.toMillis() + " ms)");
        }
        TaskRecord record = f.store.get(key).orElseThrow();
        r.output("attempts=" + record.attempts() + ", calls=" + f.downstream.calls(key) + ", status="
                + record.status() + ", effects=" + f.downstream.effects(key));
        r.expected("4 attempts, 3 delays each in [0, ceiling] with ceilings 1 s, 2 s, 4 s; DONE with one effect");
        return record.attempts() == 4 && delays.size() == 3 && withinBounds
                && backoff.ceiling(3).equals(backoff.ceiling(1).multipliedBy(4))
                && record.status() == Status.DONE && f.downstream.effects(key) == 1;
    }

    private static boolean deadLetters(Report r) throws Exception {
        FanOutConfig config = WorkerFixture.exampleConfig(4, 3, 10);
        WorkerFixture exhausted = new WorkerFixture(config, 1);
        exhausted.downstream.alwaysAnswer(Outcome.RETRYABLE);
        WorkerFixture permanent = new WorkerFixture(config, 1);
        permanent.downstream.alwaysAnswer(Outcome.NON_RETRYABLE);
        r.input(describe(exhausted, 1));
        r.input("case A: every call fails with a retryable error (503); case B: non-retryable error (400)");

        RunSummary a = exhausted.runDrivingClock(exhausted.worker("worker-1"));
        RunSummary b = permanent.runDrivingClock(permanent.worker("worker-1"));

        IdempotencyKey keyA = exhausted.key(0);
        IdempotencyKey keyB = permanent.key(0);
        r.output("case A: calls=" + exhausted.downstream.calls(keyA) + ", backoffs=" + exhausted.clock.sleeps().size()
                + ", status=" + exhausted.status(keyA) + ", " + a);
        exhausted.deadLetters().forEach(letter -> r.output("case A dead letter: " + format(letter)));
        r.output("case B: calls=" + permanent.downstream.calls(keyB) + ", backoffs=" + permanent.clock.sleeps().size()
                + ", status=" + permanent.status(keyB) + ", " + b);
        permanent.deadLetters().forEach(letter -> r.output("case B dead letter: " + format(letter)));
        r.expected("A: " + config.maxAttempts() + " calls, one RETRIES_EXHAUSTED letter, FAILED; "
                + "B: 1 call, no backoff, one NON_RETRYABLE letter, FAILED");
        return exhausted.downstream.calls(keyA) == config.maxAttempts()
                && single(exhausted.deadLetters(), Reason.RETRIES_EXHAUSTED, config.maxAttempts())
                && exhausted.status(keyA) == Status.FAILED && a.deadLettered() == 1
                && permanent.downstream.calls(keyB) == 1 && permanent.clock.sleeps().isEmpty()
                && single(permanent.deadLetters(), Reason.NON_RETRYABLE, 1)
                && permanent.status(keyB) == Status.FAILED && b.deadLettered() == 1;
    }

    private static boolean idempotency(Report r) throws Exception {
        WorkerFixture timeout = new WorkerFixture(WorkerFixture.exampleConfig(4, 3, 10), 1);
        IdempotencyKey key = timeout.key(0);
        timeout.downstream.script(key, Outcome.EFFECT_THEN_TIMEOUT, Outcome.SUCCESS);
        int tasks = 200;
        WorkerFixture publish = new WorkerFixture(WorkerFixture.exampleConfig(8, 3, 25), tasks);
        r.input("(a) " + describe(timeout, 1) + "; first call applies the effect but answers TIMEOUT");
        r.input("(b) " + describe(publish, tasks) + "; the same publication is published twice");
        r.input("(c) then target version v3 of the same publication");

        timeout.runDrivingClock(timeout.worker("worker-1"));
        publish.run(publish.worker("worker-1"));
        int callsFirst = publish.downstream.totalCalls();
        RunSummary republish = publish.run(publish.worker("worker-2"));
        int callsRepublish = publish.downstream.totalCalls() - callsFirst;
        int effectsRepublish = publish.downstream.totalEffects();
        RunSummary upgraded = WorkerFixture.await(WorkerFixture.async(
                () -> publish.worker("worker-3").run(WorkerFixture.PUBLISH_ID, "v3")));
        int callsNewVersion = publish.downstream.totalCalls() - callsFirst - callsRepublish;

        r.output("(a) key " + key.value() + ": calls=" + timeout.downstream.calls(key) + ", effects="
                + timeout.downstream.effects(key) + ", status=" + timeout.status(key));
        r.output("(b) first run calls=" + callsFirst + "; republish new calls=" + callsRepublish + ", effects="
                + effectsRepublish + ", " + republish);
        r.output("(c) new-version calls=" + callsNewVersion + ", total effects=" + publish.downstream.totalEffects()
                + ", " + upgraded);
        r.expected("(a) 2 calls, 1 effect, DONE; (b) 0 new calls, all " + tasks + " skipped, effects unchanged; "
                + "(c) " + tasks + " new keys, calls and effects");
        return timeout.downstream.calls(key) == 2 && timeout.downstream.effects(key) == 1
                && timeout.status(key) == Status.DONE
                && callsFirst == tasks && callsRepublish == 0 && republish.skipped() == tasks
                && effectsRepublish == tasks && callsNewVersion == tasks && upgraded.done() == tasks
                && publish.downstream.totalEffects() == 2 * tasks;
    }

    /**
     * Worker A completes some tasks, then freezes with C_max calls in flight (a crash: it neither completes nor
     * renews anything). After the lease TTL, worker B runs the same publication; then A "wakes up".
     */
    private static boolean crashRecovery(Report r) throws Exception {
        int tasks = 300;
        int maxConcurrency = 10;
        int doneBeforeCrash = 50;
        WorkerFixture f = new WorkerFixture(WorkerFixture.exampleConfig(maxConcurrency, 3, 20), tasks);
        r.input(describe(f, tasks) + ", lease TTL=" + f.config.leaseTtl().toMinutes() + " min");
        r.input("worker A finishes " + doneBeforeCrash + " tasks, then freezes with C_max calls in flight; "
                + "clock advances past the lease TTL; worker B runs the same publication");
        f.downstream.holdCallsAfter(doneBeforeCrash);
        CompletableFuture<RunSummary> crashedRun = WorkerFixture.async(() -> f.run(f.worker("worker-A")));
        // All C_max permits are held by blocked calls, so every earlier task has completed and released its permit.
        f.downstream.awaitHeld(maxConcurrency, WorkerFixture.SAFETY_TIMEOUT);
        Set<IdempotencyKey> doneByA = f.keysWithStatus(Status.DONE);
        int leftInProgress = f.keysWithStatus(Status.IN_PROGRESS).size();

        f.clock.advance(f.config.leaseTtl().plusSeconds(1));
        f.downstream.stopHoldingNewCalls();
        RunSummary survivor = f.run(f.worker("worker-B"));
        f.downstream.releaseHeld();
        RunSummary crashed = WorkerFixture.await(crashedRun);

        int maxCallsDone = doneByA.stream().mapToInt(f.downstream::calls).max().orElse(0);
        int maxEffects = f.allKeys().stream().mapToInt(f.downstream::effects).max().orElse(0);
        int done = f.keysWithStatus(Status.DONE).size();
        r.output("at crash: DONE=" + doneByA.size() + ", IN_PROGRESS (leases left)=" + leftInProgress);
        r.output("worker B: " + survivor);
        r.output("worker A after waking: " + crashed + " (its late completes were rejected)");
        r.output("calls per task finished before the crash=" + maxCallsDone + ", max effects per key=" + maxEffects
                + ", DONE in store=" + done + "/" + tasks);
        r.expected("B completes the " + (tasks - doneBeforeCrash) + " unfinished tasks and skips the "
                + doneBeforeCrash + " DONE ones; all DONE; 1 effect per key; A's late results discarded");
        return doneByA.size() == doneBeforeCrash && leftInProgress == maxConcurrency
                && survivor.done() == tasks - doneBeforeCrash && survivor.skipped() == doneBeforeCrash
                && maxCallsDone == 1 && maxEffects == 1 && done == tasks && crashed.done() == doneBeforeCrash;
    }

    private static boolean storeAtomicity(Report r) throws Exception {
        int contenders = 100;
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Duration ttl = Duration.ofMinutes(5);
        InMemoryProgressStore store = new InMemoryProgressStore();
        IdempotencyKey key = new IdempotencyKey("publish-1", "eng-0", "v2");
        store.createIfAbsent(key);
        r.input(contenders + " threads claim key " + key.value() + " at the same instant; lease TTL="
                + ttl.toMinutes() + " min");

        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Lease> won = new ConcurrentLinkedQueue<>();
        List<CompletableFuture<Object>> claims = IntStream.range(0, contenders)
                .mapToObj(i -> WorkerFixture.async(() -> {
                    start.await();
                    store.claim(key, "worker-" + i, now, ttl).ifPresent(won::add);
                    return null;
                }))
                .toList();
        start.countDown();
        for (CompletableFuture<Object> claim : claims) {
            WorkerFixture.await(claim);
        }
        Lease winner = won.peek();
        boolean claimedWhileLive = store.claim(key, "intruder", now.plusSeconds(1), ttl).isPresent();
        Optional<Lease> reclaimed = store.claim(key, "rescuer", now.plus(ttl).plusSeconds(1), ttl);
        boolean staleCompleted = winner != null && store.complete(winner);
        boolean newCompleted = reclaimed.isPresent() && store.complete(reclaimed.get());

        r.output("winners=" + won.stream().map(Lease::owner).toList() + ", claim while lease live="
                + claimedWhileLive + ", reclaim after expiry=" + reclaimed.isPresent());
        r.output("stale holder complete=" + staleCompleted + ", new holder complete=" + newCompleted);
        r.expected("exactly 1 winner; live lease protected; expired lease reclaimable; stale complete rejected");
        return won.size() == 1 && !claimedWhileLive && reclaimed.isPresent() && !staleCompleted && newCompleted;
    }

    // ---- Reporting -----------------------------------------------------------------------------------------

    private interface Scenario {
        boolean check(Report report) throws Exception;
    }

    /** Prints the labelled lines of one scenario. */
    private static final class Report {
        void input(String line) {
            System.out.println("  INPUT:    " + line);
        }

        void output(String line) {
            System.out.println("  OUTPUT:   " + line);
        }

        void expected(String line) {
            System.out.println("  EXPECTED: " + line);
        }
    }

    private void run(int number, String title, List<String> themes, Scenario scenario) {
        System.out.printf("[Scenario %d] %s%n  THEME:    %s%n", number, title, String.join(", ", themes));
        boolean passed;
        try {
            passed = scenario.check(new Report());
        } catch (Exception e) {
            System.out.println("  OUTPUT:   scenario threw " + e);
            passed = false;
        }
        if (!passed) {
            failures++;
        }
        System.out.println("  RESULT:   " + (passed ? "PASS" : "FAIL"));
        System.out.println();
        table.add(String.format("  %-6s [Scenario %d] %s", passed ? "PASS" : "FAIL", number, title));
    }

    private static String describe(WorkerFixture f, int engagements) {
        FanOutConfig c = f.config;
        return String.format("engagements=%d, publication=%s, target version=%s, C_max=%d, max attempts=%d, "
                        + "page size=%d (example configurable)", engagements, WorkerFixture.PUBLISH_ID,
                WorkerFixture.TARGET_VERSION, c.maxConcurrency(), c.maxAttempts(), c.pageSize());
    }

    private static String format(DeadLetter letter) {
        return "key=" + letter.key().value() + ", reason=" + letter.reason() + ", attempts=" + letter.attempts()
                + ", lastError=" + letter.lastError();
    }

    private static boolean single(List<DeadLetter> letters, Reason reason, int attempts) {
        return letters.size() == 1 && letters.getFirst().reason() == reason
                && letters.getFirst().attempts() == attempts;
    }
}
