package io.kestra.core.runners;

import java.util.List;
import java.util.Set;

import io.kestra.core.models.tasks.WorkerSelectorMatch;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Secondary;
import jakarta.inject.Singleton;

/**
 * Service interface for accessing Worker Queue routing data from a Kestra Executor.
 *
 * <p>Worker Queues are identified by their user-supplied {@code id} — the routing
 * identity used to dispatch jobs to queues. The {@code id} is immutable: tags, tenant
 * scope, and metadata may change on a queue, but its {@code id} cannot.
 */
public interface WorkerQueueMetaStore {

    /**
     * Checks whether the Worker Queue is available.
     * <p>
     * A Worker Queue is available if at least one worker is running and subscribed to it.
     *
     * @param id The Worker Queue's id - can be {@code null}.
     * @return {@code true} if the Worker Queue is available, or is {@code null}, {@code false} otherwise.
     */
    boolean isWorkerQueueAvailableForId(String id);

    /**
     * Returns the set of all existing Worker Queue ids.
     */
    Set<String> listAllWorkerQueueIds();

    /**
     * Resolves the candidate Worker Queue ids that match {@code requiredTags} under
     * the given {@code match} strategy, ordered by tiebreaker (best match first).
     *
     * <p>For {@link WorkerSelectorMatch#ALL}, candidates are queues whose tag set is a
     * superset of {@code requiredTags}; the chain is tenant specificity → tenant-scope
     * cardinality → WQ tag-set surplus → alphabetical canonical key. Cardinality is
     * effectively ≤ 1 (most-specific wins, no fall-through to less-specific queues).
     *
     * <p>For {@link WorkerSelectorMatch#ANY}, candidates are queues whose tag set
     * intersects {@code requiredTags}; the chain is tenant specificity → tenant-scope
     * cardinality → intersection size (more overlap wins) → WQ tag-set size →
     * alphabetical canonical key.
     *
     * @param requiredTags the selector tags (case-insensitive)
     * @param tenant       the tenant id, may be {@code null}
     * @param match        the match strategy; {@code null} is treated as
     *                     {@link WorkerSelectorMatch#ALL}
     * @return the candidate Worker Queue ids ordered best-first; empty when no queue matches
     */
    List<String> resolveQueueIdsByTags(Set<String> requiredTags, String tenant, WorkerSelectorMatch match);

    /**
     * Default {@link WorkerQueueMetaStore} implementation.
     * This class is only used if no other implementation exist.
     */
    @Singleton
    @Requires(missingBeans = WorkerQueueMetaStore.class)
    @Secondary
    class DefaultWorkerQueueMetaStore implements WorkerQueueMetaStore {
        @Override
        public boolean isWorkerQueueAvailableForId(String id) {
            return true;
        }

        @Override
        public Set<String> listAllWorkerQueueIds() {
            return Set.of();
        }

        @Override
        public List<String> resolveQueueIdsByTags(Set<String> requiredTags, String tenant, WorkerSelectorMatch match) {
            return List.of();
        }
    }
}
