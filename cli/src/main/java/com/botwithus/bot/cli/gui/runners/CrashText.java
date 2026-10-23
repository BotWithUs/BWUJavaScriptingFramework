package com.botwithus.bot.cli.gui.runners;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Phase;

/**
 * How a script crash reads in one line, {@code "NullPointerException in onLoop()"}.
 * Normal mode's cards, the Groups page, Installed scripts, Management and the
 * crash toast all word a crash this way, so they share this.
 */
public final class CrashText {

    /** The type named when a crash carries no exception. */
    private static final String UNKNOWN_TYPE = "Error";

    private CrashText() {}

    /** {@code "NullPointerException in onLoop()"}: the exception's simple name and where it was thrown. */
    public static String summary(LastCrash crash) {
        return typeOf(crash.cause()) + " in " + method(crash.phase());
    }

    /** The exception's simple class name, or {@code "Error"} when there is none. */
    public static String typeOf(Throwable cause) {
        return cause != null ? cause.getClass().getSimpleName() : UNKNOWN_TYPE;
    }

    /** The script method a phase runs in, as a script author would write it: {@code "onLoop()"}. */
    public static String method(Phase phase) {
        return switch (phase) {
            case ON_START -> "onStart()";
            case ON_LOOP -> "onLoop()";
            case ON_STOP -> "onStop()";
            case ON_CONFIG_UPDATE -> "onConfigUpdate()";
        };
    }
}
