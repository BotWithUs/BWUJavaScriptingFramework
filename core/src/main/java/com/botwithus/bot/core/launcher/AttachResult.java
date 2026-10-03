package com.botwithus.bot.core.launcher;

import java.util.Objects;

/**
 * What the host's attach callback did with a freshly injected client's pid.
 * The composition root supplies the callback, because only it can build a
 * connection to the agent.
 */
public sealed interface AttachResult {

    /**
     * The host is connected to the client.
     *
     * @param connectionName the connection's name in the host
     */
    record Attached(String connectionName) implements AttachResult {
        public Attached {
            Objects.requireNonNull(connectionName, "connectionName");
        }
    }

    /**
     * The host could not connect: the agent's pipe or its shared memory never appeared.
     *
     * @param message what went wrong
     */
    record Failed(String message) implements AttachResult {
        public Failed {
            Objects.requireNonNull(message, "message");
        }
    }
}
