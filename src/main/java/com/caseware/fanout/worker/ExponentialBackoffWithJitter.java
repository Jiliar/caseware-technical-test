package com.caseware.fanout.worker;

import com.caseware.fanout.model.FanOutConfig;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.random.RandomGenerator;

/**
 * Delay before retry {@code n}: full jitter over an exponential, capped ceiling,
 * {@code delay(n) = uniform[0, min(cap, base * factor^(n-1))]}.
 *
 * <p>Full jitter spreads retries of many tasks that failed together (e.g. a downstream outage), so they do
 * not hit the recovering downstream in synchronized waves.
 */
public final class ExponentialBackoffWithJitter {

    private final long baseNanos;
    private final double factor;
    private final long capNanos;
    private final RandomGenerator random;
    // Many virtual threads draw delays concurrently and RandomGenerator implementations need not be thread-safe.
    private final ReentrantLock randomLock = new ReentrantLock();

    public ExponentialBackoffWithJitter(FanOutConfig config, RandomGenerator random) {
        this.baseNanos = config.backoffBase().toNanos();
        this.factor = config.backoffFactor();
        this.capNanos = config.backoffCap().toNanos();
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Upper bound of the delay after failed attempt {@code attempt} (1-based). */
    public Duration ceiling(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        double exponential = baseNanos * Math.pow(factor, attempt - 1);
        return Duration.ofNanos((long) Math.min(capNanos, exponential));
    }

    /** A random delay in {@code [0, ceiling(attempt)]}. */
    public Duration delay(int attempt) {
        long ceilingNanos = ceiling(attempt).toNanos();
        randomLock.lock();
        try {
            return Duration.ofNanos(random.nextLong(ceilingNanos + 1));
        } finally {
            randomLock.unlock();
        }
    }
}
