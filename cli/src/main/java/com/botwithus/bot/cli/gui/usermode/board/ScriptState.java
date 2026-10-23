package com.botwithus.bot.cli.gui.usermode.board;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Objects;

/**
 * The state one script row on a client card shows. The card renders by an
 * exhaustive switch over it, so a new state is a compile error wherever it is
 * not yet drawn.
 */
public sealed interface ScriptState {

    /**
     * Looping normally.
     *
     * @param runningFor how long the current run has lasted
     */
    record Running(Duration runningFor) implements ScriptState {
        public Running {
            Objects.requireNonNull(runningFor, "runningFor");
        }
    }

    /** Registered on the client but not running, and its last run did not crash. */
    record Stopped() implements ScriptState { }

    /**
     * Still inside one {@code onLoop()} call past the watchdog's threshold.
     *
     * @param inLoop how long it has been inside that call
     */
    record Stalled(Duration inLoop) implements ScriptState {
        public Stalled {
            Objects.requireNonNull(inLoop, "inLoop");
        }
    }

    /**
     * The current run ended by throwing.
     *
     * @param summary one line, e.g. "NullPointerException in onLoop()"
     * @param at      when it crashed, on the host's wall clock
     */
    record Crashed(String summary, LocalTime at) implements ScriptState {
        public Crashed {
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(at, "at");
        }
    }

    /**
     * Ignored a stop, so the runtime cut it off from the game. Terminal until the
     * host restarts.
     */
    record CutOff() implements ScriptState { }

    /**
     * The client is not connected, so the script is not running here; it is
     * listed because the client was running it or will resume it.
     */
    record Waiting() implements ScriptState { }

    /** Stalled, crashed and cut-off scripts are what "Needs attention" counts. */
    default boolean needsAttention() {
        return switch (this) {
            case Stalled _, Crashed _, CutOff _ -> true;
            case Running _, Stopped _, Waiting _ -> false;
        };
    }

    /** Only a script looping normally counts as running. */
    default boolean isRunning() {
        return switch (this) {
            case Running _ -> true;
            case Stopped _, Stalled _, Crashed _, CutOff _, Waiting _ -> false;
        };
    }
}
