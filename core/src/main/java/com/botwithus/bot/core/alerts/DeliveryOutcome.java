package com.botwithus.bot.core.alerts;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What the launcher did with a delivery for one service. It only queues: whether
 * the message then arrived is reported later, as an {@link AlertResult}.
 *
 * @param service    the service
 * @param isQueued   the predicate: whether the launcher queued it
 * @param deliveryId the id its {@link AlertResult} will carry, when it was queued
 * @param dropReason why it was not queued, when it was not
 */
public record DeliveryOutcome(AlertService service, boolean isQueued, OptionalLong deliveryId,
                              Optional<DropReason> dropReason) {

    /** Why the launcher did not queue a delivery for a service. */
    public enum DropReason {
        /** The service has no webhook URL, or ntfy no topic, on the launcher. */
        NOT_CONFIGURED,
        /** The launcher's queue for the service is full. */
        QUEUE_FULL,
        /** The launcher already queued a message with the same dedupe key; nothing is lost. */
        DUPLICATE,
        /** A reason this host does not know. */
        UNKNOWN
    }

    public DeliveryOutcome {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(dropReason, "dropReason");
    }
}
