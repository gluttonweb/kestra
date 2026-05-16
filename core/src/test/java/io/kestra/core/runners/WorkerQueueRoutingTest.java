package io.kestra.core.runners;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.kestra.core.models.tasks.WorkerQueueFallback;
import io.kestra.core.worker.WorkerQueues;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerQueueRoutingTest {

    @Test
    void shouldExposeDisposition() {
        WorkerQueueRouting routing = new WorkerQueueRouting(
            "docker", List.of("docker"), WorkerQueueFallback.WAIT, WorkerQueueRouting.Disposition.WAIT_AND_DISPATCH);

        assertThat(routing.disposition()).isEqualTo(WorkerQueueRouting.Disposition.WAIT_AND_DISPATCH);
    }

    @Test
    void toDefaultShouldHaveDispatchDisposition() {
        WorkerQueueRouting routing = WorkerQueueRouting.toDefault();

        assertThat(routing.workerQueueId()).isEqualTo(WorkerQueues.DEFAULT_ID);
        assertThat(routing.disposition()).isEqualTo(WorkerQueueRouting.Disposition.DISPATCH);
        assertThat(routing.isDefault()).isTrue();
    }

    @Test
    void forSystemShouldHaveDispatchDisposition() {
        WorkerQueueRouting routing = WorkerQueueRouting.forSystem();

        assertThat(routing.workerQueueId()).isEqualTo(WorkerQueues.SYSTEM_ID);
        assertThat(routing.disposition()).isEqualTo(WorkerQueueRouting.Disposition.DISPATCH);
        assertThat(routing.isSystem()).isTrue();
    }

    @Test
    void shouldDefineFourDispositionValues() {
        assertThat(WorkerQueueRouting.Disposition.values()).containsExactlyInAnyOrder(
            WorkerQueueRouting.Disposition.DISPATCH,
            WorkerQueueRouting.Disposition.WAIT_AND_DISPATCH,
            WorkerQueueRouting.Disposition.FAIL,
            WorkerQueueRouting.Disposition.CANCEL);
    }
}
