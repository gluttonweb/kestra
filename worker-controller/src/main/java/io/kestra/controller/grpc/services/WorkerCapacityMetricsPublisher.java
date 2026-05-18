package io.kestra.controller.grpc.services;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import io.kestra.core.metrics.MetricRegistry;
import io.kestra.core.worker.QueueSubscription;

import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes per-(worker group, worker queue) capacity gauges from the controller's live
 * {@link WorkerJobDispatcher} state, so the webserver can serve a live capacity snapshot
 * without a separate cross-process RPC.
 *
 * <p>The four metrics — see {@link MetricRegistry#METRIC_CONTROLLER_CAPACITY_SUBSCRIPTION_ALLOCATED}
 * and siblings — flow into the controller's {@link io.kestra.core.server.ServiceInstance#metrics()}
 * via the existing heartbeat path, and the webserver reads them from
 * {@link io.kestra.core.server.ServiceLivenessStore}.
 *
 * <p>Gauges are registered lazily the first time a {@code (groupId, queueId)} pair is
 * observed, then reused; when a pair has no active workers a tick simply resets the
 * gauge to zero. This keeps cardinality bounded by the number of (group, queue) pairs
 * that have ever been served by this controller instance.
 */
@Slf4j
@Singleton
@Requires(property = "kestra.server-type", pattern = "(CONTROLLER|STANDALONE)")
public class WorkerCapacityMetricsPublisher {

    private final WorkerJobDispatcher dispatcher;
    private final MetricRegistry metricRegistry;

    private final ConcurrentHashMap<SubKey, AtomicInteger> subscriptionAllocated = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SubKey, AtomicInteger> subscriptionUsed = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> sharedAllocated = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> sharedUsed = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> groupInflight = new ConcurrentHashMap<>();

    @Inject
    public WorkerCapacityMetricsPublisher(WorkerJobDispatcher dispatcher, MetricRegistry metricRegistry) {
        this.dispatcher = dispatcher;
        this.metricRegistry = metricRegistry;
    }

    @Scheduled(fixedDelay = "5s", initialDelay = "5s")
    public void publish() {
        try {
            Map<SubKey, Integer> subAlloc = new HashMap<>();
            Map<SubKey, Integer> subUsed = new HashMap<>();
            Map<String, Integer> sharedAlloc = new HashMap<>();
            Map<String, Integer> sharedUsedAgg = new HashMap<>();
            Map<String, Integer> inflight = new HashMap<>();

            for (WorkerStreamContext<?> ctx : dispatcher.activeStreams()) {
                String groupId = ctx.getWorkerGroupId();
                for (QueueSubscription sub : ctx.getQueueSubscriptions()) {
                    String qid = sub.normalizedWorkerQueueId();
                    SubKey key = new SubKey(groupId, qid);
                    subAlloc.merge(key, ctx.guaranteedCapacity(qid), Integer::sum);
                    subUsed.merge(key, ctx.guaranteedUsed(qid), Integer::sum);
                }
                sharedAlloc.merge(groupId, ctx.sharedCapacity(), Integer::sum);
                sharedUsedAgg.merge(groupId, ctx.sharedUsed(), Integer::sum);
                inflight.merge(groupId, ctx.getInFlightCount(), Integer::sum);
            }

            Function<SubKey, String[]> subTags = k -> metricRegistry.workerGroupAndQueueTags(k.workerGroupId(), k.workerQueueId());
            Function<String, String[]> groupTags = metricRegistry::workerGroupTags;

            publishGauge(subscriptionAllocated, subAlloc,
                MetricRegistry.METRIC_CONTROLLER_CAPACITY_SUBSCRIPTION_ALLOCATED,
                MetricRegistry.METRIC_CONTROLLER_CAPACITY_SUBSCRIPTION_ALLOCATED_DESCRIPTION,
                subTags);
            publishGauge(subscriptionUsed, subUsed,
                MetricRegistry.METRIC_CONTROLLER_CAPACITY_SUBSCRIPTION_USED,
                MetricRegistry.METRIC_CONTROLLER_CAPACITY_SUBSCRIPTION_USED_DESCRIPTION,
                subTags);
            publishGauge(sharedAllocated, sharedAlloc,
                MetricRegistry.METRIC_CONTROLLER_CAPACITY_SHARED_ALLOCATED,
                MetricRegistry.METRIC_CONTROLLER_CAPACITY_SHARED_ALLOCATED_DESCRIPTION,
                groupTags);
            publishGauge(sharedUsed, sharedUsedAgg,
                MetricRegistry.METRIC_CONTROLLER_CAPACITY_SHARED_USED,
                MetricRegistry.METRIC_CONTROLLER_CAPACITY_SHARED_USED_DESCRIPTION,
                groupTags);
            publishGauge(groupInflight, inflight,
                MetricRegistry.METRIC_CONTROLLER_WORKER_GROUP_JOB_INFLIGHT,
                MetricRegistry.METRIC_CONTROLLER_WORKER_GROUP_JOB_INFLIGHT_DESCRIPTION,
                groupTags);
        } catch (Exception e) {
            log.warn("Failed to publish worker capacity metrics", e);
        }
    }

    private <K> void publishGauge(
        ConcurrentHashMap<K, AtomicInteger> gauges,
        Map<K, Integer> values,
        String metricName,
        String metricDescription,
        Function<K, String[]> tagsFn
    ) {
        values.forEach((key, value) -> {
            AtomicInteger gauge = gauges.computeIfAbsent(key, k -> {
                AtomicInteger v = new AtomicInteger();
                metricRegistry.gauge(metricName, metricDescription, (Supplier<Integer>) v::get, tagsFn.apply(k));
                return v;
            });
            gauge.set(value);
        });
        zeroIfMissing(gauges, values.keySet());
    }

    private static <K> void zeroIfMissing(Map<K, AtomicInteger> gauges, Set<K> present) {
        gauges.forEach((k, v) -> {
            if (!present.contains(k)) {
                v.set(0);
            }
        });
    }

    record SubKey(String workerGroupId, String workerQueueId) {}
}
