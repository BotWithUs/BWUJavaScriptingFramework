package com.botwithus.bot.cli.gui.pages.groups;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * One script on one client.
 *
 * @param name      the script's name, as its manifest gives it
 * @param avgLoopMs the average {@code onLoop()} time while it runs; empty otherwise
 * @param detail    one line about the state, such as "NullPointerException in
 *                  onLoop()" for a crash; empty when there is nothing to add
 */
public record ScriptFact(String name, ScriptState state, OptionalDouble avgLoopMs, String detail) {

    public ScriptFact {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(avgLoopMs, "avgLoopMs");
        Objects.requireNonNull(detail, "detail");
    }

    /** A script in {@code state} with no loop time and nothing more to say. */
    public static ScriptFact of(String name, ScriptState state) {
        return new ScriptFact(name, state, OptionalDouble.empty(), "");
    }

    /** A running script and its average loop time. */
    public static ScriptFact running(String name, double avgLoopMs) {
        return new ScriptFact(name, ScriptState.RUNNING, OptionalDouble.of(avgLoopMs), "");
    }

    /** Whether this is the script called {@code script}; names match ignoring case, as the runtime's do. */
    public boolean isNamed(String script) {
        return name.equalsIgnoreCase(script);
    }
}
