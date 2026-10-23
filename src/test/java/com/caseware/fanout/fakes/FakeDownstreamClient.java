package com.caseware.fanout.fakes;

import com.caseware.fanout.model.IdempotencyKey;
import com.caseware.fanout.port.DownstreamClient;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntSupplier;

/**
 * Scriptable downstream that behaves like an idempotent service (one effect per key, however many calls) and
 * records what a test needs: simultaneous calls, calls per key and effects per key.
 *
 * <p>Calls can be held open to create overlap deterministically (no timing assumptions): either until
 * {@code n} calls are inside at once, or every call after the first {@code k}.
 */
public final class FakeDownstreamClient implements DownstreamClient {

    /** What one scripted call does. */
    public enum Outcome {
        SUCCESS,
        RETRYABLE,
        NON_RETRYABLE,
        /** Applies the effect, then answers as if the call had timed out. */
        EFFECT_THEN_TIMEOUT
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final Map<IdempotencyKey, Deque<Outcome>> scripts = new HashMap<>();
    private final Map<IdempotencyKey, Integer> calls = new HashMap<>();
    private final Set<IdempotencyKey> effects = new HashSet<>();
    private Outcome defaultOutcome = Outcome.SUCCESS;
    private int inFlight;
    private int maxConcurrent;
    private int totalCalls;
    private int completedCalls;
    private CountDownLatch concurrencyGate = new CountDownLatch(0);
    private int holdAfter = Integer.MAX_VALUE;
    private CountDownLatch heldGate = new CountDownLatch(1);
    private int held;

    /** Outcomes for successive calls of {@code key}; once used up, calls succeed. */
    public void script(IdempotencyKey key, Outcome... outcomes) {
        lock.lock();
        try {
            scripts.put(key, new ArrayDeque<>(List.of(outcomes)));
        } finally {
            lock.unlock();
        }
    }

    /** Outcome of every call that has no script. */
    public void alwaysAnswer(Outcome outcome) {
        lock.lock();
        try {
            defaultOutcome = outcome;
        } finally {
            lock.unlock();
        }
    }

    /** The first {@code n} calls wait until all {@code n} are in flight together, then everything flows. */
    public void holdUntilConcurrent(int n) {
        lock.lock();
        try {
            concurrencyGate = new CountDownLatch(n);
        } finally {
            lock.unlock();
        }
    }

    /** Every call after the first {@code k} (counted from now on) blocks until {@link #releaseHeld()}. */
    public void holdCallsAfter(int k) {
        lock.lock();
        try {
            holdAfter = totalCalls + k;
            heldGate = new CountDownLatch(1);
        } finally {
            lock.unlock();
        }
    }

    /** New calls are no longer held; calls already held stay blocked until {@link #releaseHeld()}. */
    public void stopHoldingNewCalls() {
        lock.lock();
        try {
            holdAfter = Integer.MAX_VALUE;
        } finally {
            lock.unlock();
        }
    }

    public void releaseHeld() {
        lock.lock();
        try {
            holdAfter = Integer.MAX_VALUE;
            heldGate.countDown();
        } finally {
            lock.unlock();
        }
    }

    /** Blocks until {@code n} calls are held; {@code safetyTimeout} only guards against a hung test. */
    public void awaitHeld(int n, Duration safetyTimeout) throws InterruptedException {
        lock.lock();
        try {
            long remaining = safetyTimeout.toNanos();
            while (held < n) {
                if (remaining <= 0) {
                    throw new IllegalStateException("expected " + n + " held calls, saw " + held);
                }
                remaining = changed.awaitNanos(remaining);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result call(IdempotencyKey key, Duration timeout) {
        CountDownLatch gate;
        boolean hold;
        lock.lock();
        try {
            totalCalls++;
            calls.merge(key, 1, Integer::sum);
            inFlight++;
            maxConcurrent = Math.max(maxConcurrent, inFlight);
            gate = concurrencyGate;
            hold = totalCalls > holdAfter;
            if (hold) {
                held++;
                changed.signalAll();
            }
        } finally {
            lock.unlock();
        }
        try {
            gate.countDown();
            gate.await();
            if (hold) {
                heldGate.await();
            }
            return answer(key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RetryableFailure("INTERRUPTED");
        } finally {
            lock.lock();
            try {
                inFlight--;
                completedCalls++;
                if (hold) {
                    held--;
                }
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    private Result answer(IdempotencyKey key) {
        lock.lock();
        try {
            Deque<Outcome> script = scripts.get(key);
            Outcome outcome = script == null || script.isEmpty() ? defaultOutcome : script.poll();
            return switch (outcome) {
                case SUCCESS -> {
                    effects.add(key);
                    yield new Success();
                }
                case EFFECT_THEN_TIMEOUT -> {
                    effects.add(key);
                    yield new RetryableFailure(RetryableFailure.TIMEOUT);
                }
                case RETRYABLE -> new RetryableFailure("503 Service Unavailable");
                case NON_RETRYABLE -> new NonRetryableFailure("400 Bad Request");
            };
        } finally {
            lock.unlock();
        }
    }

    public int maxConcurrent() {
        return read(() -> maxConcurrent);
    }

    public int totalCalls() {
        return read(() -> totalCalls);
    }

    public int completedCalls() {
        return read(() -> completedCalls);
    }

    public int calls(IdempotencyKey key) {
        return read(() -> calls.getOrDefault(key, 0));
    }

    /** Distinct effects applied for {@code key}: 0 or 1, because the service is idempotent by key. */
    public int effects(IdempotencyKey key) {
        return read(() -> effects.contains(key) ? 1 : 0);
    }

    public int totalEffects() {
        return read(effects::size);
    }

    private int read(IntSupplier value) {
        lock.lock();
        try {
            return value.getAsInt();
        } finally {
            lock.unlock();
        }
    }
}
