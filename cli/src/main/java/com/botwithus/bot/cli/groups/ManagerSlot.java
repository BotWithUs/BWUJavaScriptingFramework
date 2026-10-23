package com.botwithus.bot.cli.groups;

import java.util.Objects;

/**
 * The management script assigned to a group, and whether it should be running.
 * Stopping everything on a group pauses its manager by clearing
 * {@code shouldRun}, so the manager does not restart what was just stopped.
 *
 * @param script    the management script's name
 * @param shouldRun whether the host should keep the manager running
 */
public record ManagerSlot(String script, boolean shouldRun) {

    public ManagerSlot {
        Objects.requireNonNull(script, "script");
        if (script.isBlank()) {
            throw new IllegalArgumentException("manager script name is blank");
        }
    }
}
