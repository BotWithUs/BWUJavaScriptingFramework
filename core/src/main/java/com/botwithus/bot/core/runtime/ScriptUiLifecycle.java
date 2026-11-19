package com.botwithus.bot.core.runtime;

import java.util.function.Consumer;

/**
 * The plugin-UI hooks a runner calls at the start and end of each run, by owner
 * name. {@code opened} lets the owner create components again after an earlier run
 * was released; {@code released} destroys what the owner made and stops it making
 * more. Both are the connection's {@code GameAPIImpl}, or for a management script a
 * fan-out over every connection.
 *
 * @param opened   called on the run's own thread before any script code runs
 * @param released called when the run ends (stop, crash, {@code onStart} throw) and
 *                 again when the watchdog revokes it; must be safe to call twice
 */
public record ScriptUiLifecycle(Consumer<String> opened, Consumer<String> released) {

    /** Does nothing: a runtime nobody wired plugin UI into. */
    public static final ScriptUiLifecycle NONE = new ScriptUiLifecycle(owner -> { }, owner -> { });

    public ScriptUiLifecycle {
        if (opened == null || released == null) {
            throw new IllegalArgumentException("opened and released are required");
        }
    }
}
