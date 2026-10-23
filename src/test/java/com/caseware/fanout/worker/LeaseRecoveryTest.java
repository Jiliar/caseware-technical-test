package com.caseware.fanout.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.fakes.WorkerFixture;
import com.caseware.fanout.model.IdempotencyKey;
import com.caseware.fanout.model.RunSummary;
import com.caseware.fanout.port.ProgressStore.Status;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class LeaseRecoveryTest {

    /**
     * Worker A completes some tasks, then freezes with C_max calls in flight (a crash: it neither completes nor
     * renews anything). After the lease TTL, worker B runs the same publication; then A "wakes up".
     */
    @Test
    void shouldResumeWithoutRepeatingDoneTasks_whenAWorkerCrashesAndItsLeasesExpire() throws Exception {
        int tasks = 300;
        int maxConcurrency = 10;
        int doneBeforeCrash = 50;
        WorkerFixture f = new WorkerFixture(WorkerFixture.exampleConfig(maxConcurrency, 3, 20), tasks);
        f.downstream.holdCallsAfter(doneBeforeCrash);
        CompletableFuture<RunSummary> crashedRun = WorkerFixture.async(() -> f.run(f.worker("worker-A")));
        // All C_max permits are held by blocked calls, so every earlier task has completed and released its permit.
        f.downstream.awaitHeld(maxConcurrency, WorkerFixture.SAFETY_TIMEOUT);
        Set<IdempotencyKey> doneByA = f.keysWithStatus(Status.DONE);
        Set<IdempotencyKey> inFlightAtCrash = f.keysWithStatus(Status.IN_PROGRESS);

        f.clock.advance(f.config.leaseTtl().plusSeconds(1));
        f.downstream.stopHoldingNewCalls();
        RunSummary survivor = f.run(f.worker("worker-B"));

        assertThat(doneByA).hasSize(doneBeforeCrash);
        assertThat(inFlightAtCrash).hasSize(maxConcurrency);
        assertThat(survivor).isEqualTo(new RunSummary(tasks - doneBeforeCrash, 0, doneBeforeCrash));
        assertThat(doneByA).allSatisfy(key -> assertThat(f.downstream.calls(key)).isEqualTo(1));
        assertThat(f.keysWithStatus(Status.DONE)).hasSize(tasks);

        // A's calls now return, but B reclaimed those leases: A's late completes are rejected.
        f.downstream.releaseHeld();
        assertThat(WorkerFixture.await(crashedRun).done()).isEqualTo(doneBeforeCrash);
        assertThat(inFlightAtCrash).allSatisfy(key -> {
            assertThat(f.downstream.calls(key)).isEqualTo(2);
            assertThat(f.downstream.effects(key)).isEqualTo(1);
        });
    }
}
