package io.kestra.controller.grpc.services;

import java.util.List;

import io.kestra.controller.grpc.WorkerJobResponse;
import io.kestra.core.metrics.MetricConfig;
import io.kestra.core.metrics.MetricRegistry;
import io.kestra.core.worker.QueueSubscription;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class WorkerCapacityMetricsPublisherTest {

    private SimpleMeterRegistry meterRegistry;
    private MetricRegistry metricRegistry;
    private WorkerJobDispatcher dispatcher;
    private WorkerCapacityMetricsPublisher publisher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metricRegistry = new MetricRegistry(meterRegistry, new MetricConfig());
        dispatcher = Mockito.mock(WorkerJobDispatcher.class);
        publisher = new WorkerCapacityMetricsPublisher(dispatcher, metricRegistry);
    }

    // Per-subscription allocation gauges only carry a value when reservations are
    // in play; those scenarios are covered by the EE-side test class.

    @Test
    void shouldTagDefaultQueueWithDefaultSentinel() {
        WorkerStreamContext<WorkerJobResponse> worker = context("w1", "group-a", 5,
            QueueSubscription.DEFAULT
        );
        when(dispatcher.activeStreams()).thenReturn(List.of(worker));

        publisher.publish();

        // Shared bucket holds the full 5 slots — default queue has no reservation.
        assertThat(sharedGaugeValue(MetricRegistry.METRIC_CONTROLLER_CAPACITY_SHARED_ALLOCATED, "group-a"))
            .isEqualTo(5.0);
        // Gauge for the default sub is registered with the "default" tag, value 0 (no reservation).
        assertThat(gaugeValue(MetricRegistry.METRIC_CONTROLLER_CAPACITY_SUBSCRIPTION_ALLOCATED, "group-a", "default"))
            .isZero();
    }

    // shouldKeepGaugesIsolatedBetweenGroups moved to EE — it asserts per-subscription
    // allocation gauges that only carry a value when reservations are in play.

    private double gaugeValue(String name, String workerGroup, String workerQueue) {
        Gauge gauge = meterRegistry.find(name)
            .tag(MetricRegistry.TAG_WORKER_GROUP, workerGroup)
            .tag(MetricRegistry.TAG_WORKER_QUEUE, workerQueue)
            .gauge();
        assertThat(gauge).as("gauge %s {%s=%s, %s=%s}", name,
            MetricRegistry.TAG_WORKER_GROUP, workerGroup,
            MetricRegistry.TAG_WORKER_QUEUE, workerQueue).isNotNull();
        return gauge.value();
    }

    private double sharedGaugeValue(String name, String workerGroup) {
        Gauge gauge = meterRegistry.find(name)
            .tag(MetricRegistry.TAG_WORKER_GROUP, workerGroup)
            .gauge();
        assertThat(gauge).as("gauge %s {%s=%s}", name, MetricRegistry.TAG_WORKER_GROUP, workerGroup).isNotNull();
        return gauge.value();
    }

    @SuppressWarnings("unchecked")
    private static WorkerStreamContext<WorkerJobResponse> context(String id, String groupId, int max, QueueSubscription... subs) {
        StreamObserver<WorkerJobResponse> obs = Mockito.mock(StreamObserver.class);
        WorkerStreamContext<WorkerJobResponse> ctx = new WorkerStreamContext<>(id, groupId, List.of(subs), max, obs);
        ctx.setPermits(max);
        return ctx;
    }
}
