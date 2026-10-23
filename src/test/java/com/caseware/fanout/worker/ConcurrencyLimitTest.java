package com.caseware.fanout.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.fakes.WorkerFixture;
import com.caseware.fanout.model.RunSummary;
import com.caseware.fanout.port.ProgressStore.Status;
import org.junit.jupiter.api.Test;

class ConcurrencyLimitTest {

    @Test
    void shouldNeverExceedConcurrencyLimit_whenFortyThousandTasksAreProcessed() throws Exception {
        int tasks = 40_000;
        int maxConcurrency = 50;
        WorkerFixture f = new WorkerFixture(WorkerFixture.exampleConfig(maxConcurrency, 3, 100), tasks);
        // The first C_max calls wait until they all overlap: the limit is reached, and any extra call would show.
        f.downstream.holdUntilConcurrent(maxConcurrency);

        RunSummary summary = f.run(f.worker("worker-1"));

        assertThat(f.downstream.maxConcurrent()).isEqualTo(maxConcurrency);
        assertThat(summary.done()).isEqualTo(tasks);
        assertThat(f.keysWithStatus(Status.DONE)).hasSize(tasks);
    }
}
