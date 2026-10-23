package com.caseware.fanout.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.fakes.FakeDownstreamClient.Outcome;
import com.caseware.fanout.fakes.WorkerFixture;
import com.caseware.fanout.model.IdempotencyKey;
import com.caseware.fanout.model.RunSummary;
import com.caseware.fanout.port.ProgressStore.Status;
import org.junit.jupiter.api.Test;

class IdempotencyTest {

    @Test
    void shouldNotDuplicateTheEffect_whenACallThatAppliedItTimedOutAndIsRetried() throws Exception {
        WorkerFixture f = new WorkerFixture(WorkerFixture.exampleConfig(4, 3, 10), 1);
        IdempotencyKey key = f.key(0);
        f.downstream.script(key, Outcome.EFFECT_THEN_TIMEOUT, Outcome.SUCCESS);

        RunSummary summary = f.runDrivingClock(f.worker("worker-1"));

        assertThat(f.downstream.calls(key)).isEqualTo(2);
        assertThat(f.downstream.effects(key)).isEqualTo(1);
        assertThat(f.status(key)).isEqualTo(Status.DONE);
        assertThat(summary.done()).isEqualTo(1);
    }

    @Test
    void shouldMakeNoNewCalls_whenTheSamePublicationIsPublishedAgain() throws Exception {
        int tasks = 200;
        WorkerFixture f = new WorkerFixture(WorkerFixture.exampleConfig(8, 3, 25), tasks);
        f.run(f.worker("worker-1"));
        int callsAfterFirstRun = f.downstream.totalCalls();

        RunSummary republish = f.run(f.worker("worker-2"));

        assertThat(callsAfterFirstRun).isEqualTo(tasks);
        assertThat(f.downstream.totalCalls()).isEqualTo(callsAfterFirstRun);
        assertThat(f.downstream.totalEffects()).isEqualTo(tasks);
        assertThat(republish).isEqualTo(new RunSummary(0, 0, tasks));
    }
}
