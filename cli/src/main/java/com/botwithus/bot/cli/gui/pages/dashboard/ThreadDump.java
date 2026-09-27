package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.cli.output.AnsiCodes;
import com.botwithus.bot.core.runtime.ScriptRunner;

import java.io.PrintStream;

/** Prints where a script's thread is right now, for a runner that looks stuck. */
final class ThreadDump {

    private ThreadDump() {}

    /** @param pipe the connection the runner is on, as the console names it */
    static void print(ScriptRunner runner, String script, String pipe, PrintStream out) {
        out.println(AnsiCodes.colorize("> threads " + script + " on " + pipe, AnsiCodes.YELLOW));
        StackTraceElement[] frames = runner.threadStackTrace();
        if (frames.length == 0) {
            out.println("\"script-" + script + "\" is not alive.");
            return;
        }
        out.println("\"script-" + script + "\" " + runner.liveness());
        for (StackTraceElement frame : frames) {
            out.println("  at " + frame);
        }
    }
}
