package com.caseware.fanout.port;

import java.time.Instant;

/** Source of the current time; injected so leases and backoff can be tested without real waiting. */
public interface Clock {

    Instant now();
}
