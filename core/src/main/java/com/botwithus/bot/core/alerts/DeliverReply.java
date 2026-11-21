package com.botwithus.bot.core.alerts;

import java.util.List;
import java.util.Objects;

/** How a hand-over to the launcher ended. */
public sealed interface DeliverReply {

    /** The launcher answered, with what it did for each service. */
    record Answered(List<DeliveryOutcome> outcomes) implements DeliverReply {
        public Answered {
            outcomes = List.copyOf(outcomes);
        }
    }

    /**
     * The launcher refused the request itself, e.g. a field out of its limits. Sending
     * it again would be refused again, so it is not kept for later.
     *
     * @param why the launcher's words, for the log; never contains the message
     */
    record Rejected(String why) implements DeliverReply {
        public Rejected {
            Objects.requireNonNull(why, "why");
        }
    }

    /**
     * Nothing was handed over: no launcher is connected, it does not send alerts,
     * or it did not answer. The message has not been sent.
     *
     * @param why for the log; never contains the message
     */
    record Unavailable(String why) implements DeliverReply {
        public Unavailable {
            Objects.requireNonNull(why, "why");
        }
    }
}
