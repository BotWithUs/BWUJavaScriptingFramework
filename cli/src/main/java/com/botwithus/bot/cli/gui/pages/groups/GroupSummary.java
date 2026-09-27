package com.botwithus.bot.cli.gui.pages.groups;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The row of four figures under a group's name.
 *
 * @param running      members running a script
 * @param size         members, unresolved ones included
 * @param needsLook    members with a stalled, crashed or cut-off script
 * @param mainScript   the script most members show; empty when none shows one
 * @param otherScripts how many other scripts members show
 * @param avgLoopMs    the average loop time across running members; empty when none runs
 * @param connected    members connected now
 * @param stoppable    scripts running on connected members: what Stop all stops
 * @param queued       starts waiting for members to be back: what Stop all cancels
 */
public record GroupSummary(int running, int size, int needsLook, Optional<String> mainScript, int otherScripts,
                           OptionalDouble avgLoopMs, int connected, int stoppable, int queued) {

    public GroupSummary {
        Objects.requireNonNull(mainScript, "mainScript");
        Objects.requireNonNull(avgLoopMs, "avgLoopMs");
    }

    /** Whether Stop all has anything to do. */
    public boolean canStopAll() {
        return stoppable > 0 || queued > 0;
    }
}
