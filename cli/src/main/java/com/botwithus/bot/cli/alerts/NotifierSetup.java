package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.Notifier;
import com.botwithus.bot.core.alerts.SendResult;

import java.time.Instant;
import java.util.Objects;

/** Whether a service can be sent to right now, and with what. */
public sealed interface NotifierSetup {

    /** The status a send reports when the service is not set up. */
    String NOT_SET_UP = "Not set up";

    /** The service is set up; send with {@code notifier}. */
    record Ready(Notifier notifier) implements NotifierSetup {
        public Ready {
            Objects.requireNonNull(notifier, "notifier");
        }
    }

    /**
     * The service is missing something the user has to fill in.
     *
     * @param reason what to do, e.g. {@code Add the webhook URL first}; never contains a secret
     */
    record NotReady(String reason) implements NotifierSetup {
        public NotReady {
            Objects.requireNonNull(reason, "reason");
        }

        /** This as the result of a send that could not be made. */
        public SendResult.Failed asResult(Instant at) {
            return new SendResult.Failed(at, NOT_SET_UP, reason, 0);
        }
    }
}
