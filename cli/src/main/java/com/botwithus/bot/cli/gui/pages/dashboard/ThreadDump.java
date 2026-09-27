package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.cli.output.AnsiCodes;
import com.botwithus.bot.core.runtime.ScriptRunner;

import java.io.PrintStream;

/** Prints where a script's thread is right now, for a runner that looks stuck. */
final class ThreadDump {

    private ThreadDump() {}

    static void print(ScriptRunner runner, RunnerRef ref, PrintStream out) {
        out.println(AnsiCodes.colorize("> threads " + ref.script() + " on " + ref.client(), AnsiCodes.YELLOW));
        StackTraceElement[] frames = runner.threadStackTrace();
        if (frames.length == 0) {
            out.println("\"script-" + ref.script() + "\" is not alive.");
            return;
        }
        out.println("\"script-" + ref.script() + "\" " + runner.liveness());
        for (StackTraceElement frame : frames) {
            out.println("  at " + frame);
        }
    }
}
