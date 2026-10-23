package com.caseware.fanout.model;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class FanOutConfigTest {

    // Example values: a 2-minute call plus a 30-second backoff must fit inside one lease.
    private static final Duration CALL_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration BACKOFF_CAP = Duration.ofSeconds(30);

    @Test
    void shouldRejectTheLease_whenItDoesNotOutliveACallPlusABackoff() {
        assertThatNoException().isThrownBy(() -> config(Duration.ofSeconds(151)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> config(Duration.ofSeconds(150)))
                .withMessage("leaseTtl must be > callTimeout + backoffCap");
    }

    private static FanOutConfig config(Duration leaseTtl) {
        return new FanOutConfig(10, 3, Duration.ofSeconds(1), 2.0, BACKOFF_CAP, CALL_TIMEOUT, leaseTtl, 10);
    }
}
