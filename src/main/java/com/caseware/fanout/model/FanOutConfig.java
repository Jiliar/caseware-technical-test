package com.caseware.fanout.model;

import java.time.Duration;
import java.util.Objects;

/**
 * Worker configuration, validated on construction. There are no built-in defaults: the downstream owner (or
 * the operator) sets every value.
 *
 * @param maxConcurrency {@code C_max}: simultaneous downstream calls, equal to the agreed downstream capacity
 * @param maxRetries     retries after the first attempt (total attempts {@code 1 + maxRetries})
 * @param backoffBase    ceiling of the first retry delay
 * @param backoffFactor  growth of the ceiling per attempt
 * @param backoffCap     maximum ceiling
 * @param callTimeout    {@code T_call}: timeout of one downstream call
 * @param leaseTtl       lease duration. Each attempt renews the lease, so the longest stretch without a renewal
 *                       is one call plus the backoff after it (or plus the dead-letter send): hence
 *                       {@code leaseTtl > callTimeout + backoffCap}
 * @param pageSize       targets requested per page
 */
public record FanOutConfig(int maxConcurrency, int maxRetries, Duration backoffBase, double backoffFactor,
                           Duration backoffCap, Duration callTimeout, Duration leaseTtl, int pageSize) {

    public FanOutConfig {
        Objects.requireNonNull(backoffBase, "backoffBase");
        Objects.requireNonNull(backoffCap, "backoffCap");
        Objects.requireNonNull(callTimeout, "callTimeout");
        Objects.requireNonNull(leaseTtl, "leaseTtl");
        require(maxConcurrency >= 1, "maxConcurrency must be >= 1");
        require(maxRetries >= 0, "maxRetries must be >= 0");
        require(backoffBase.isPositive(), "backoffBase must be > 0");
        require(backoffFactor >= 1.0, "backoffFactor must be >= 1");
        require(backoffCap.compareTo(backoffBase) >= 0, "backoffCap must be >= backoffBase");
        require(callTimeout.isPositive(), "callTimeout must be > 0");
        require(leaseTtl.compareTo(callTimeout.plus(backoffCap)) > 0, "leaseTtl must be > callTimeout + backoffCap");
        require(pageSize >= 1, "pageSize must be >= 1");
    }

    public int maxAttempts() {
        return 1 + maxRetries;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
