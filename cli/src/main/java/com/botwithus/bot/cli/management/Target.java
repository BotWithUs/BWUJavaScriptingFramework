package com.botwithus.bot.cli.management;

import com.botwithus.bot.cli.groups.GroupId;

import java.util.Objects;

/**
 * One thing a management script is told to manage. A script's targets decide
 * what its orchestrator sees and may act on; see {@link Scope}.
 */
public sealed interface Target {

    /** Every client on the host. Adding it replaces a script's other targets. */
    record Host() implements Target {

        @Override
        public String key() {
            return "host";
        }
    }

    /**
     * Every member of a group, whatever it is called now: the id survives a rename.
     * A group target is the group's manager slot, so a group has at most one.
     */
    record Group(GroupId id) implements Target {

        public Group {
            Objects.requireNonNull(id, "id");
        }

        @Override
        public String key() {
            return "group-" + id;
        }
    }

    /**
     * One script on the client on one account.
     *
     * @param accountUuid the client's account UUID
     * @param scriptName  the client script's manifest name
     */
    record ClientScript(String accountUuid, String scriptName) implements Target {

        public ClientScript {
            Objects.requireNonNull(accountUuid, "accountUuid");
            Objects.requireNonNull(scriptName, "scriptName");
            if (accountUuid.isBlank() || scriptName.isBlank()) {
                throw new IllegalArgumentException("a client script target needs an account and a script");
            }
        }

        @Override
        public String key() {
            return "client-" + accountUuid + "-" + scriptName;
        }
    }

    /**
     * A stable text form, unique per target, for keeping things per target such
     * as settings. Not meant for display, and not guaranteed to be a safe file name.
     */
    String key();

    /** The whole-host target. */
    static Target host() {
        return new Host();
    }
}
