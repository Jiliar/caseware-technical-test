package com.caseware.fanout.fakes;

import com.caseware.fanout.port.Clock;
import com.caseware.fanout.port.Sleeper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link Clock} and {@link Sleeper} on simulated time: time moves only when a test calls {@link #advance} or
 * {@link #runUntilDone}, so backoff and lease expiry are exercised without real waiting.
 */
public final class FakeClock implements Clock, Sleeper {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final List<Instant> deadlines = new ArrayList<>();
    private final List<Duration> sleeps = new ArrayList<>();
    private Instant now;

    public FakeClock(Instant start) {
        this.now = start;
    }

    @Override
    public Instant now() {
        lock.lock();
        try {
            return now;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void sleep(Duration duration) throws InterruptedException {
        lock.lock();
        try {
            sleeps.add(duration);
            if (!duration.isPositive()) {
                return;
            }
            Instant deadline = now.plus(duration);
            deadlines.add(deadline);
            changed.signalAll();
            try {
                while (now.isBefore(deadline)) {
                    changed.await();
                }
            } finally {
                deadlines.remove(deadline);
                changed.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    public void advance(Duration duration) {
        lock.lock();
        try {
            now = now.plus(duration);
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Every duration passed to {@link #sleep}, in call order. */
    public List<Duration> sleeps() {
        lock.lock();
        try {
            return List.copyOf(sleeps);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Discrete-event driver: whenever every sleeping thread is waiting for a future instant, jumps to the
     * earliest one; returns when {@code work} completes. {@code safetyTimeout} only guards against a hung
     * test (no progress at all), it is never part of the expected path.
     */
    public void runUntilDone(CompletableFuture<?> work, Duration safetyTimeout) throws InterruptedException {
        work.whenComplete((result, error) -> poke());
        lock.lock();
        try {
            while (!work.isDone()) {
                boolean everyoneWaiting = !deadlines.isEmpty() && deadlines.stream().allMatch(now::isBefore);
                if (everyoneWaiting) {
                    now = deadlines.stream().min(Instant::compareTo).orElseThrow();
                    changed.signalAll();
                } else if (!changed.await(safetyTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                    throw new IllegalStateException("no progress within " + safetyTimeout);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void poke() {
        lock.lock();
        try {
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
