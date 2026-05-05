package io.kestra.core.runners;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerJobEventTest {

    @Test
    void shouldReturnEmptyKeyForDefaultQueueWhenNull() {
        WorkerJobEvent event = new WorkerJobEvent(null, null);
        assertThat(event.key()).isEqualTo("");
    }

    @Test
    void shouldReturnEmptyKeyForExplicitlyEmptyQueueId() {
        WorkerJobEvent event = new WorkerJobEvent("", null);
        assertThat(event.key()).isEqualTo("");
    }

    @Test
    void shouldReturnIdAsRoutingKeyForNamedQueue() {
        WorkerJobEvent event = new WorkerJobEvent("gpu", null);

        assertThat(event.key()).isEqualTo("gpu");
        assertThat(event.workerQueueId()).isEqualTo("gpu");
    }

    @Test
    void shouldDistinguishDistinctIdsInRoutingKey() {
        WorkerJobEvent a = new WorkerJobEvent("id-aaa", null);
        WorkerJobEvent b = new WorkerJobEvent("id-bbb", null);

        assertThat(a.key()).isNotEqualTo(b.key());
    }
}
