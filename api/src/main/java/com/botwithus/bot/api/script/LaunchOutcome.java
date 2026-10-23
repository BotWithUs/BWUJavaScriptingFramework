package com.botwithus.bot.api.script;

import java.util.Objects;

/** How a {@link LaunchHandle} ended. */
public sealed interface LaunchOutcome {

    /**
     * The client was injected and the host attached to it.
     *
     * @param connectionName the host's name for the connection, as
     *                       {@link com.botwithus.bot.api.ClientProvider} knows it
     */
    record Attached(String connectionName) implements LaunchOutcome {
        public Attached {
            Objects.requireNonNull(connectionName, "connectionName");
        }
    }

    /**
     * The launch failed before the client was injected, or the client exited
     * first.
     *
     * @param code    the service's failure code, for example {@code "launch_failed"};
     *                {@code "exited"} when the process ended before injection;
     *                {@code "service_unavailable"} when the service went away
     *                and the launch could not be found again
     * @param message English text for logs
     */
    record Failed(String code, String message) implements LaunchOutcome {
        public Failed {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * The service injected the client, but the host could not attach to it:
     * its pipe or its shared memory never appeared.
     *
     * @param message what went wrong
     */
    record AttachFailed(String message) implements LaunchOutcome {
        public AttachFailed {
            Objects.requireNonNull(message, "message");
        }
    }
}
