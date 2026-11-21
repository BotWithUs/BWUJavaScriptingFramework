package com.botwithus.bot.core.alerts;

import java.util.Objects;

/** How asking the launcher for a Send test ended. */
public sealed interface TestReply {

    /** The test is queued; its {@link AlertResult} will carry {@code deliveryId}. */
    record Queued(long deliveryId) implements TestReply { }

    /**
     * The launcher refused it, e.g. because the service is not set up.
     *
     * @param message what to do, e.g. {@code Add the webhook URL first}
     */
    record Refused(String message) implements TestReply {
        public Refused {
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * Nothing was queued: no launcher is connected, it does not send alerts, or it
     * did not answer.
     *
     * @param message what to tell the user
     */
    record Unavailable(String message) implements TestReply {
        public Unavailable {
            Objects.requireNonNull(message, "message");
        }
    }
}
