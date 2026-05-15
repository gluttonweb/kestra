package io.kestra.controller.grpc.services;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
            Map<SubKey, int[]> subAgg = new HashMap<>();
            Map<String, int[]> sharedAgg = new HashMap<>();
            Map<String, Integer> inflightAgg = new HashMap<>();

            for (WorkerStreamContext<?> ctx : dispatcher.activeStreams()) {
                String groupId = ctx.getWorkerGroupId();
                for (QueueSubscription sub : ctx.getQueueSubscriptions()) {
                    String qid = sub.normalizedWorkerQueueId();
                    int alloc = ctx.guaranteedCapacity(qid);
                    AtomicInteger usedCounter = ctx.getGuaranteedUsed().get(qid);
                    int used = usedCounter != null ? usedCounter.get() : 0;
                    int[] acc = subAgg.computeIfAbsent(new SubKey(groupId, qid), k -> new int[2]);
                    acc[0] += alloc;
                    acc[1] += used;
                }
                int[] sharedAcc = sharedAgg.computeIfAbsent(groupId, k -> new int[2]);
                sharedAcc[0] += ctx.sharedCapacity();
                sharedAcc[1] += ctx.getSharedUsed().get();
                inflightAgg.merge(groupId, ctx.getInFlightCount(), Integer::sum);
            }

            updateSubscriptionGauges(subAgg);
            updateSharedGauges(sharedAgg);
            updateInflightGauges(inflightAgg);
        } catch (Exception e) {
            log.warn("Failed to publish worker capacity metrics", e);
        }
    }

    private void updateSubscriptionGauges(Map<SubKey, int[]> subAgg) {
        for (Map.Entry<SubKey, int[]> e : subAgg.entrySet()) {
            SubKey key = e.getKey();
            registerSubGaugesIfAbsent(key);
            subscriptionAllocated.get(key).set(e.getValue()[0]);
            subscriptionUsed.get(key).set(e.getValue()[1]);
        }
        zeroIfMissing(subscriptionAllocated, subAgg.keySet());
        zeroIfMissing(subscriptionUsed, subAgg.keySet());
    }

    private void updateSharedGauges(Map<String, int[]> sharedAgg) {
        for (Map.Entry<String, int[]> e : sharedAgg.entrySet()) {
            String groupId = e.getKey();
            registerSharedGaugesIfAbsent(groupId);
            sharedAllocated.get(groupId).set(e.getValue()[0]);
            sharedUsed.get(groupId).set(e.getValue()[1]);
        }
        zeroIfMissing(sharedAllocated, sharedAgg.keySet());
        zeroIfMissing(sharedUsed, sharedAgg.keySet());
    }

    private void updateInflightGauges(Map<String, Integer> inflightAgg) {
        for (Map.Entry<String, Integer> e : inflightAgg.entrySet()) {
            String groupId = e.getKey();
            registerInflightGaugeIfAbsent(groupId);
            groupInflight.get(groupId).set(e.getValue());
        }
        zeroIfMissing(groupInflight, inflightAgg.keySet());
    }

    private void registerSubGaugesIfAbsent(SubKey key) {
        subscriptionAllocated.computeIfAbsent(key, k -> {
            AtomicInteger value = new AtomicInteger();
            String[] tags = metricRegistry.workerGroupAndQueueTags(k.workerGroupId(), k.workerQueueId());
            metricRegistry.gauge(
                MetricRegistry.METRIC_CONTROLLER_CAPACITY_SUBSCRIPTION_ALLOCATED,
                MetricRegistry.METRIC_CONTROLLER_CAPACITY_SUBSCRIPTION_ALLOCATED_DESCRIPTION,
                (Supplier<Integer>) value::get,
                tags
            );
            return value;
        });
        subscriptionUsed.computeIfAbsent(key, k -> {
            AtomicInteger value = new AtomicInteger();
            String[] tags = metricRegistry.workerGroupAndQueueTags(k.workerGroupId(), k.workerQueueId());
            metricRegistry.gauge(
                MetricRegistry.METRIC_CONTROLLER_CAPACITY_SUBSCRIPTION_USED,
                MetricRegistry.METRIC_CONTROLLER_CAPACITY_SUBSCRIPTION_USED_DESCRIPTION,
                (Supplier<Integer>) value::get,
                tags
            );
            return value;
        });
    }

    private void registerSharedGaugesIfAbsent(String groupId) {
        sharedAllocated.computeIfAbsent(groupId, k -> {
            AtomicInteger value = new AtomicInteger();
            String[] tags = metricRegistry.workerGroupTags(k);
            metricRegistry.gauge(
                MetricRegistry.METRIC_CONTROLLER_CAPACITY_SHARED_ALLOCATED,
                MetricRegistry.METRIC_CONTROLLER_CAPACITY_SHARED_ALLOCATED_DESCRIPTION,
                (Supplier<Integer>) value::get,
                tags
            );
            return value;
        });
        sharedUsed.computeIfAbsent(groupId, k -> {
            AtomicInteger value = new AtomicInteger();
            String[] tags = metricRegistry.workerGroupTags(k);
            metricRegistry.gauge(
                MetricRegistry.METRIC_CONTROLLER_CAPACITY_SHARED_USED,
                MetricRegistry.METRIC_CONTROLLER_CAPACITY_SHARED_USED_DESCRIPTION,
                (Supplier<Integer>) value::get,
                tags
            );
            return value;
        });
    }

    private void registerInflightGaugeIfAbsent(String groupId) {
        groupInflight.computeIfAbsent(groupId, k -> {
            AtomicInteger value = new AtomicInteger();
            String[] tags = metricRegistry.workerGroupTags(k);
            metricRegistry.gauge(
                MetricRegistry.METRIC_CONTROLLER_WORKER_GROUP_JOB_INFLIGHT,
                MetricRegistry.METRIC_CONTROLLER_WORKER_GROUP_JOB_INFLIGHT_DESCRIPTION,
                (Supplier<Integer>) value::get,
                tags
            );
            return value;
        });
    }

    private static <K> void zeroIfMissing(Map<K, AtomicInteger> values, Set<K> present) {
        values.forEach((k, v) -> {
            if (!present.contains(k)) {
                v.set(0);
            }
        });
    }

    record SubKey(String workerGroupId, String workerQueueId) {}
}
