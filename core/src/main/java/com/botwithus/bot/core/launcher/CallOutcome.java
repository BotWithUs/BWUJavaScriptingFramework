package com.botwithus.bot.core.launcher;

import com.botwithus.msgpack.MsgValue;

import java.util.Objects;

/** How one call to the service ended, for callers that word every ending themselves. */
sealed interface CallOutcome {

    /** The service answered {@code ok}, with {@code body}. */
    record Answered(MsgValue body) implements CallOutcome {
        public Answered {
            Objects.requireNonNull(body, "body");
        }
    }

    /** The service answered with an error. */
    record Refused(WireError error) implements CallOutcome {
        public Refused {
            Objects.requireNonNull(error, "error");
        }
    }

    /**
     * No service answered: none is connected, or the call timed out.
     *
     * @param message for the log
     */
    record NoAnswer(String message) implements CallOutcome {
        public NoAnswer {
            Objects.requireNonNull(message, "message");
        }
    }
}
