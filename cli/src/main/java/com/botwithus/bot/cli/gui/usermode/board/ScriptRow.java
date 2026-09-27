package com.botwithus.bot.cli.gui.usermode.board;

import java.util.Objects;
import java.util.Optional;

/**
 * One script on a client card.
 *
 * @param script    the script's details
 * @param state     what the row shows
 * @param laneNanos the last loop times, oldest first; empty when the script is not running
 * @param avgLoopMs the average loop time, or {@code 0} before the first loop
 * @param managedBy the management script that names this script on this
 *                  client, directly or by a group, if any: the row's robot link
 *                  to Management. One that manages the whole host is left out,
 *                  since it would sit on every row
 */
public record ScriptRow(ScriptInfo script, ScriptState state, long[] laneNanos, double avgLoopMs,
                        Optional<String> managedBy) {

    private static final long[] NO_LOOPS = new long[0];

    public ScriptRow {
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(laneNanos, "laneNanos");
        Objects.requireNonNull(managedBy, "managedBy");
    }

    /** A row with no loop history, for a script that is not running. */
    public static ScriptRow idle(ScriptInfo script, ScriptState state) {
        return new ScriptRow(script, state, NO_LOOPS, 0, Optional.empty());
    }

    /** The name the runtime registers the script under. */
    public String name() {
        return script.name();
    }

    /** This row, managed by {@code manager}. */
    public ScriptRow withManagedBy(Optional<String> manager) {
        return new ScriptRow(script, state, laneNanos, avgLoopMs, manager);
    }

    /** This row with {@code next} as its state and no loop history. */
    public ScriptRow withState(ScriptState next) {
        return new ScriptRow(script, next, NO_LOOPS, 0, managedBy);
    }
}
