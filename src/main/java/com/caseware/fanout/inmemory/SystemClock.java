package com.caseware.fanout.inmemory;

import com.caseware.fanout.port.Clock;
import com.caseware.fanout.port.Sleeper;
import java.time.Duration;
import java.time.Instant;

/** Production {@link Clock} and {@link Sleeper} on wall-clock time; tests use a fake instead. */
public final class SystemClock implements Clock, Sleeper {

    @Override
    public Instant now() {
        return Instant.now();
    }

    @Override
    public void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(duration);
    }
}
