package io.kestra.core.worker;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.kestra.core.utils.Enums;
import io.micronaut.core.annotation.Nullable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * A worker's subscription to a Worker Queue, identified by the queue's
 * {@code workerQueueId} (the user-supplied PK of an existing Worker Queue, or the
 * reserved {@link WorkerQueues#DEFAULT_ID} sentinel for the global default queue).
 *
 * <p>{@code reservedPercent} is the minimum percentage of a worker's slots guaranteed
 * available to this Worker Queue, in {@code [1, 100]}. The sentinel value {@code -1}
 * (see {@link #NO_RESERVATION}) means "no reservation" — the subscription consumes
 * only unreserved (shared) capacity.
 *
 * <p>The sum of reserved percentages across a worker's subscriptions must be
 * {@code <= 100}; the remainder forms a shared pool that any subscribed Worker Queue may use.
 *
 * <p>{@code mode} controls how this subscription's reserved slots interact with other
 * subscriptions on the same worker — see {@link Mode} for the {@code STRICT} (default,
 * back-compat) versus {@code ELASTIC} (lendable / borrowable) semantics.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QueueSubscription(
    @NotBlank String workerQueueId,
    @Min(-1) @Max(100) int reservedPercent,
    @Nullable Mode mode) {

    /** Sentinel value meaning "no reservation for this Worker Queue". */
    public static final int NO_RESERVATION = -1;

    /**
     * Reservation interaction mode — lender-only semantics.
     *
     * <p>{@link #STRICT} (default): the subscription's reserved slots are exclusive —
     * no other subscription on the same worker may use them, even when idle. Back-compat
     * default and the right choice for hard-isolation workloads.
     *
     * <p>{@link #ELASTIC}: the subscription's idle reserved slots may be borrowed by
     * any other subscription on the same worker. The reserved percentage becomes a
     * soft floor — borrowed slots are not preempted, so a busy lender may temporarily
     * fall short of its full floor until borrowed jobs complete naturally. Whether
     * <em>this</em> subscription borrows from elsewhere is independent of its mode:
     * borrowing-from-others is universal and not gated by the borrower's mode.
     */
    public enum Mode {
        STRICT,
        ELASTIC,
        UNKNOWN;

        @JsonCreator
        public static Mode fromString(final String value) {
            return Enums.getForNameIgnoreCase(value, Mode.class, UNKNOWN);
        }
    }

    /** Default mode applied when {@code mode} is null on the wire (back-compat). */
    public static final Mode DEFAULT_MODE = Mode.STRICT;

    public QueueSubscription {
        if (workerQueueId == null || workerQueueId.isBlank()) {
            throw new IllegalArgumentException("workerQueueId must not be null or blank");
        }
        if (reservedPercent != NO_RESERVATION && (reservedPercent < 1 || reservedPercent > 100)) {
            throw new IllegalArgumentException(
                "reservedPercent must be -1 (no reservation) or in [1, 100], got " + reservedPercent);
        }
        // null = field absent (legacy / pre-Mode payload), UNKNOWN = unrecognized value
        // (forward-rolled-back / typo / external integration). Both degrade safely to STRICT.
        if (mode == null || mode == Mode.UNKNOWN) {
            mode = DEFAULT_MODE;
        }
    }

    /** Two-arg overload preserved for back-compat (defaults {@code mode} to STRICT). */
    public QueueSubscription(String workerQueueId, int reservedPercent) {
        this(workerQueueId, reservedPercent, DEFAULT_MODE);
    }

    /** Subscription to the global default queue with no reservation. */
    public static final QueueSubscription DEFAULT = new QueueSubscription(WorkerQueues.DEFAULT_ID, NO_RESERVATION, DEFAULT_MODE);

    /**
     * Returns the dispatch-side routing key used by the internal
     * {@code workerJobEventQueue}: empty string for the default queue (whether expressed
     * as the {@link WorkerQueues#DEFAULT_ID} sentinel), or the id verbatim for named queues.
     */
    public String normalizedWorkerQueueId() {
        return WorkerQueues.isDefault(workerQueueId) ? "" : workerQueueId;
    }

    /**
     * Returns {@code true} if this subscription reserves capacity (i.e., reservedPercent > 0).
     */
    public boolean hasReservation() {
        return reservedPercent > 0;
    }

    /**
     * Returns {@code true} if this subscription is in {@link Mode#ELASTIC} mode —
     * i.e., its idle reserved slots may be borrowed by any other subscription on the
     * same worker. Borrowing-from-others is universal and not gated by mode, so this
     * predicate strictly describes the lender stance of <em>this</em> subscription.
     */
    public boolean isElastic() {
        return mode == Mode.ELASTIC;
    }
}
