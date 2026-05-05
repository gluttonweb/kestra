package io.kestra.core.runners;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@KestraTest
class WorkerQueueMetaStoreTest {
    @Inject
    private WorkerQueueMetaStore workerGroupMetaStore;

    @Test
    void isWorkerQueueAvailableForId() {
        assertTrue(workerGroupMetaStore.isWorkerQueueAvailableForId("toto"));
    }

    @Test
    void listAllWorkerQueueIds() {
        Set<String> uids = workerGroupMetaStore.listAllWorkerQueueIds();

        assertThat(uids).isEmpty();
    }
}
