package com.botwithus.bot.cli.log;

import com.botwithus.bot.core.runlog.HostIdentity;
import com.botwithus.bot.core.runlog.RunLogLogback;
import com.botwithus.bot.core.runlog.RunLogs;

import java.time.Clock;

/**
 * Turns on script run logs for this host process: one {@link RunLogs} at the
 * spec location, wired to logback, to the {@code System.out}/{@code err} tee and
 * to the JVM's default uncaught-exception handler. Called once by each entry
 * point, after {@link LogCapture#install()}.
 */
public final class RunLogBootstrap {

    private RunLogBootstrap() {
    }

    /** Starts run logging and returns the instance to hand to the {@code CliContext}. */
    public static RunLogs start(LogCapture capture) {
        RunLogs runLogs = RunLogs.atDefaultRoot(HostIdentity.current(RunLogBootstrap.class),
                Clock.systemUTC());
        RunLogLogback.install(runLogs);
        capture.setRunLogs(runLogs);
        // The JVM's own hook, set once here at the composition root. It covers
        // threads a script spawns (a script's own thread has a handler of its
        // own): the trace is logged on the dying thread, so it lands in the
        // script's run log, and the run gets a breadcrumb for it.
        Thread.setDefaultUncaughtExceptionHandler(
                runLogs.uncaughtHandler(Thread.getDefaultUncaughtExceptionHandler()));
        return runLogs;
    }
}
