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

    @Test
    void shouldRegisterPerSubscriptionGaugesWhenWorkersConnect() {
        WorkerStreamContext<WorkerJobResponse> worker = context("w1", "group-a", 10,
            new QueueSubscription("gpu", 60),
            new QueueSubscription("cpu", 30)
        );
        // 6 = 60% of 10 reserved for gpu; consume 2.
        worker.tryReserveBucket("gpu");
        worker.tryReserveBucket("gpu");
        when(dispatcher.activeStreams()).thenReturn(List.of(worker));

        publisher.publish();

        assertThat(gaugeValue(MetricRegistry.METRIC_CONTROLLER_CAPACITY_SUBSCRIPTION_ALLOCATED, "group-a", "gpu"))
            .isEqualTo(6.0);
        assertThat(gaugeValue(MetricRegistry.METRIC_CONTROLLER_CAPACITY_SUBSCRIPTION_USED, "group-a", "gpu"))
            .isEqualTo(2.0);
        assertThat(gaugeValue(MetricRegistry.METRIC_CONTROLLER_CAPACITY_SUBSCRIPTION_ALLOCATED, "group-a", "cpu"))
            .isEqualTo(3.0);
        assertThat(gaugeValue(MetricRegistry.METRIC_CONTROLLER_CAPACITY_SUBSCRIPTION_USED, "group-a", "cpu"))
            .isZero();
        // Shared = 10 - 6 - 3 = 1.
        assertThat(sharedGaugeValue(MetricRegistry.METRIC_CONTROLLER_CAPACITY_SHARED_ALLOCATED, "group-a"))
            .isEqualTo(1.0);
        assertThat(sharedGaugeValue(MetricRegistry.METRIC_CONTROLLER_CAPACITY_SHARED_USED, "group-a"))
            .isZero();
    }

    @Test
    void shouldSumAcrossWorkersInSameGroup() {
        WorkerStreamContext<WorkerJobResponse> w1 = context("w1", "group-a", 10,
            new QueueSubscription("gpu", 60));
        WorkerStreamContext<WorkerJobResponse> w2 = context("w2", "group-a", 20,
            new QueueSubscription("gpu", 60));
        when(dispatcher.activeStreams()).thenReturn(List.of(w1, w2));

        publisher.publish();

        // 6 + 12 = 18 allocated to gpu across the two workers in group-a.
        assertThat(gaugeValue(MetricRegistry.METRIC_CONTROLLER_CAPACITY_SUBSCRIPTION_ALLOCATED, "group-a", "gpu"))
            .isEqualTo(18.0);
    }

    @Test
    void shouldZeroOutStaleEntriesWhenWorkersDisconnect() {
        WorkerStreamContext<WorkerJobResponse> worker = context("w1", "group-a", 10,
            new QueueSubscription("gpu", 60));
        worker.tryReserveBucket("gpu");
        when(dispatcher.activeStreams()).thenReturn(List.of(worker));
        publisher.publish();
        assertThat(gaugeValue(MetricRegistry.METRIC_CONTROLLER_CAPACITY_SUBSCRIPTION_USED, "group-a", "gpu"))
            .isEqualTo(1.0);

        // When the worker disconnects, the next tick must drop the value back to 0.
        when(dispatcher.activeStreams()).thenReturn(List.of());
        publisher.publish();

        assertThat(gaugeValue(MetricRegistry.METRIC_CONTROLLER_CAPACITY_SUBSCRIPTION_USED, "group-a", "gpu"))
            .isZero();
        assertThat(gaugeValue(MetricRegistry.METRIC_CONTROLLER_CAPACITY_SUBSCRIPTION_ALLOCATED, "group-a", "gpu"))
            .isZero();
    }

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

    @Test
    void shouldKeepGaugesIsolatedBetweenGroups() {
        WorkerStreamContext<WorkerJobResponse> wa = context("wa", "group-a", 10,
            new QueueSubscription("gpu", 60));
        WorkerStreamContext<WorkerJobResponse> wb = context("wb", "group-b", 10,
            new QueueSubscription("gpu", 40));
        when(dispatcher.activeStreams()).thenReturn(List.of(wa, wb));

        publisher.publish();

        assertThat(gaugeValue(MetricRegistry.METRIC_CONTROLLER_CAPACITY_SUBSCRIPTION_ALLOCATED, "group-a", "gpu"))
            .isEqualTo(6.0);
        assertThat(gaugeValue(MetricRegistry.METRIC_CONTROLLER_CAPACITY_SUBSCRIPTION_ALLOCATED, "group-b", "gpu"))
            .isEqualTo(4.0);
    }

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
