package com.caseware.fanout.port;

import java.time.Duration;

/**
 * Waits for a duration on the matching {@link Clock}; injected so retry delays are testable without real
 * waiting. An interrupted wait throws {@link InterruptedException}.
 */
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;
}
