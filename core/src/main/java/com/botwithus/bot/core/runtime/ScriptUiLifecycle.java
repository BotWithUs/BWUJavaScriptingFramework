package com.botwithus.bot.core.runtime;

import java.util.function.LongSupplier;
import java.util.function.ObjLongConsumer;

/**
 * The plugin-UI hooks a runner calls at the start and end of each run, by owner
 * name and <b>run id</b>.
 *
 * <p>The run id is what makes a release per-run rather than per-name. A reload can
 * leave a quarantined run of a script still alive, register a fresh run of the same
 * name over it, and only then let the old run finish its cleanup. Released by name,
 * that late cleanup would destroy the fresh run's components and close the name, so
 * every later create of the fresh run would fail. Released with the old run's id, it
 * is a no-op once a newer run has opened.</p>
 *
 * @param runIds   issues a run id; unique among the runs this lifecycle serves
 * @param opened   called with {@code (owner, runId)} on the run's own thread before any
 *                 script code runs; makes this run the owner's current one
 * @param released called with {@code (owner, runId)} when the run ends (stop, crash,
 *                 {@code onStart} throw) and again when the watchdog revokes it; does
 *                 nothing once a newer run of the owner has opened, and is safe to call
 *                 twice
 */
public record ScriptUiLifecycle(LongSupplier runIds, ObjLongConsumer<String> opened,
                                ObjLongConsumer<String> released) {

    /** Does nothing: a runtime nobody wired plugin UI into. */
    public static final ScriptUiLifecycle NONE =
            new ScriptUiLifecycle(() -> 0L, (owner, run) -> { }, (owner, run) -> { });

    public ScriptUiLifecycle {
        if (runIds == null || opened == null || released == null) {
            throw new IllegalArgumentException("runIds, opened and released are required");
        }
    }
}
