package com.botwithus.bot.core.alerts;

import java.util.Objects;
import java.util.Optional;

/** How a change to a service's set-up on the launcher ended. */
public sealed interface IntegrationReply {

    /** The launcher saved it; {@code state} is the service as it is now. */
    record Updated(IntegrationState state) implements IntegrationReply {
        public Updated {
            Objects.requireNonNull(state, "state");
        }
    }

    /**
     * The launcher refused it.
     *
     * @param field   which part was refused, e.g. {@code secret} or {@code topic}, when it said
     * @param message what to fix, e.g. {@code The webhook URL must start with https://}; never the value
     */
    record Refused(Optional<String> field, String message) implements IntegrationReply {
        public Refused {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * Nothing was changed: no launcher is connected, it does not send alerts, or it
     * did not answer.
     *
     * @param message what to tell the user
     */
    record Unavailable(String message) implements IntegrationReply {
        public Unavailable {
            Objects.requireNonNull(message, "message");
        }
    }
}
