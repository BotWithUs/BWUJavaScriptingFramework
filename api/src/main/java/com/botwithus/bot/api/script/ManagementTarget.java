package com.botwithus.bot.api.script;

import java.util.Objects;

/**
 * Something a {@link ManagementScript} has been told to manage. The user picks
 * a management script's targets on the host; the script reads them through
 * {@link ManagementContext#targets()}.
 *
 * <p>The host keeps the script to its targets: the {@link ClientOrchestrator}
 * and {@link com.botwithus.bot.api.ClientProvider} it is given only see and act
 * on the clients and scripts its targets cover. A call on anything else comes
 * back as a failed result rather than an exception.</p>
 *
 * @see ManagementContext#targets()
 */
public sealed interface ManagementTarget {

    /**
     * Every client on the host, and every script on each. A script with this
     * target sees everything, and is the only kind that may create, delete or
     * change groups.
     */
    record WholeHost() implements ManagementTarget { }

    /**
     * Every client in a group, and every script on each.
     *
     * @param id   the group's id; it never changes, so it is the thing to keep
     * @param name the group's name as it is now; it changes when the group is
     *             renamed, and it is what the {@link ClientOrchestrator}'s group
     *             methods take
     */
    record Group(String id, String name) implements ManagementTarget {
        public Group {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * One script on one client.
     *
     * @param accountUuid the client's account UUID, which survives the client
     *                    restarting on a new pipe
     * @param scriptName  the script's manifest name
     */
    record ClientScript(String accountUuid, String scriptName) implements ManagementTarget {
        public ClientScript {
            Objects.requireNonNull(accountUuid, "accountUuid");
            Objects.requireNonNull(scriptName, "scriptName");
        }
    }
}
