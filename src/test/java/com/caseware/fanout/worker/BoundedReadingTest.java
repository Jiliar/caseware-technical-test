package com.caseware.fanout.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.fakes.WorkerFixture;
import com.caseware.fanout.model.RunSummary;
import com.caseware.fanout.port.EngagementTargetSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedReadingTest {

    @Test
    void shouldReadNoFurtherThanTasksInFlightAllow_whenThePublicationIsLarge() throws Exception {
        int tasks = 500;
        int maxConcurrency = 8;
        int pageSize = 10;
        WorkerFixture f = new WorkerFixture(WorkerFixture.exampleConfig(maxConcurrency, 3, pageSize), tasks);
        AtomicInteger read = new AtomicInteger();
        List<Integer> unfinishedAtPageRequest = new ArrayList<>();
        EngagementTargetSource recording = (publishId, token, limit) -> {
            // Targets read whose call has not returned yet, at the moment the worker asks for more.
            unfinishedAtPageRequest.add(read.get() - f.downstream.completedCalls());
            EngagementTargetSource.Page page = f.source.page(publishId, token, limit);
            read.addAndGet(page.engagementIds().size());
            return page;
        };
        f.downstream.holdCallsAfter(0);
        CompletableFuture<RunSummary> run = WorkerFixture.async(() -> f.run(f.worker("worker-1", recording)));

        f.downstream.awaitHeld(maxConcurrency, WorkerFixture.SAFETY_TIMEOUT);
        int readWhileDownstreamBlocked = read.get();
        f.downstream.releaseHeld();
        RunSummary summary = WorkerFixture.await(run);

        assertThat(readWhileDownstreamBlocked).isLessThanOrEqualTo(maxConcurrency + pageSize);
        assertThat(unfinishedAtPageRequest).allSatisfy(unfinished ->
                assertThat(unfinished).isLessThanOrEqualTo(maxConcurrency));
        assertThat(unfinishedAtPageRequest).hasSize(tasks / pageSize);
        assertThat(summary.done()).isEqualTo(tasks);
    }
}
