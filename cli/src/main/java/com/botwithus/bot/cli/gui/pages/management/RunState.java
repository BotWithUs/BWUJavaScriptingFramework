package com.botwithus.bot.cli.gui.pages.management;

import java.time.Duration;
import java.util.Objects;

/** Whether a management script runs now, and if not, whether its last run crashed. */
public sealed interface RunState {

    /** Running, for {@code uptime} since it last started. */
    record Running(Duration uptime) implements RunState {
        public Running {
            Objects.requireNonNull(uptime, "uptime");
        }
    }

    /** Not running, and its last run did not crash. */
    record Stopped() implements RunState { }

    /**
     * Not running, because its last run crashed.
     *
     * @param summary one line, e.g. "IllegalStateException in onStart()"
     */
    record Crashed(String summary) implements RunState {
        public Crashed {
            Objects.requireNonNull(summary, "summary");
        }
    }

    /** "Running", "Stopped" or "Crashed". */
    default String label() {
        return switch (this) {
            case Running _ -> "Running";
            case Stopped _ -> "Stopped";
            case Crashed _ -> "Crashed";
        };
    }

    /** The uptime after "Running" as a clock, "42:10"; empty otherwise. */
    default String detail() {
        return switch (this) {
            case Running running -> ManagementText.uptime(running.uptime());
            case Stopped _, Crashed _ -> "";
        };
    }

    default boolean isRunning() {
        return switch (this) {
            case Running _ -> true;
            case Stopped _, Crashed _ -> false;
        };
    }

    default boolean isCrashed() {
        return switch (this) {
            case Crashed _ -> true;
            case Running _, Stopped _ -> false;
        };
    }
}
