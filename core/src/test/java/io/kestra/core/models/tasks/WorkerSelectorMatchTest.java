package io.kestra.core.models.tasks;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerSelectorMatchTest {

    @Test
    void shouldDefineTwoValues() {
        assertThat(WorkerSelectorMatch.values())
            .containsExactlyInAnyOrder(WorkerSelectorMatch.ALL, WorkerSelectorMatch.ANY);
    }
}
