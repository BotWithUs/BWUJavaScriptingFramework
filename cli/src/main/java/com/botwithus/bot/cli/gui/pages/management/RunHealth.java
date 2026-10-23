package com.botwithus.bot.cli.gui.pages.management;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * How a management script's runner is doing.
 *
 * @param avgLoopMs its average loop while it runs; empty before its first loop
 * @param loops     loops it has made since the host loaded it
 * @param crashes   times it has crashed since the host loaded it
 * @param lastCrash its latest crash, "IllegalStateException in onStart() · 13:10", if it has crashed
 */
public record RunHealth(RunState state, OptionalDouble avgLoopMs, long loops, long crashes,
                        Optional<String> lastCrash) {

    public RunHealth {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(avgLoopMs, "avgLoopMs");
        Objects.requireNonNull(lastCrash, "lastCrash");
    }

    /** The list's loop cell, "4", or a dash when it is not running. */
    public String loopText() {
        return state.isRunning() ? ManagementText.loopMs(avgLoopMs) : ManagementText.NONE;
    }

    /** The Health section's state line: "running 42:10", "crashed" or "stopped". */
    public String stateLine() {
        return switch (state) {
            case RunState.Running _ -> "running " + state.detail();
            case RunState.Crashed _ -> "crashed";
            case RunState.Stopped _ -> "stopped";
        };
    }
}
