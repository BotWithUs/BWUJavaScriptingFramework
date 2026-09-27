package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.core.runtime.ScriptProfiler;
import com.botwithus.bot.core.runtime.ScriptRunner;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Turns a client's script runners into card rows: which of the five states
 * each one is in, and what it shows.
 */
final class ClientRows {

    private ClientRows() {}

    /** The row for {@code runner}, measured at {@code now} and {@code nowNanos}. */
    static ScriptRow rowOf(ScriptRunner runner, ScriptInfo info, Instant now, long nowNanos, ZoneId zone) {
        ScriptState state = stateOf(runner, now, nowNanos, zone);
        if (!state.isRunning()) {
            return ScriptRow.idle(info, state);
        }
        ScriptProfiler profiler = runner.getProfiler();
        return new ScriptRow(info, state, profiler.recentLoopNanos(), profiler.avgLoopMs(), Optional.empty());
    }

    /**
     * What {@code runner}'s row shows. Cut off wins over everything, because a
     * runner that ignored a stop can still look busy; a running runner is
     * stalled while the watchdog says so; a stopped one crashed only if its
     * current run did.
     *
     * @param nowNanos the {@link System#nanoTime()} to measure a stall against
     * @param zone     the zone a crash's time is shown in
     */
    static ScriptState stateOf(ScriptRunner runner, Instant now, long nowNanos, ZoneId zone) {
        switch (runner.liveness()) {
            case REVOKED, ABANDONED -> {
                return new ScriptState.CutOff();
            }
            case STALLED -> {
                if (runner.isRunning()) {
                    long inLoop = Math.max(0L, runner.livenessState().millisInLoop(nowNanos));
                    return new ScriptState.Stalled(Duration.ofMillis(inLoop));
                }
            }
            case LIVE -> { }
        }
        if (runner.isRunning()) {
            return new ScriptState.Running(runningFor(runner.lastStartedAt(), now));
        }
        return currentCrash(runner)
                .<ScriptState>map(crash -> new ScriptState.Crashed(crashSummary(crash),
                        LocalTime.ofInstant(crash.when(), zone)))
                .orElseGet(ScriptState.Stopped::new);
    }

    private static Duration runningFor(Instant startedAt, Instant now) {
        if (startedAt == null || now.isBefore(startedAt)) {
            return Duration.ZERO;
        }
        return Duration.between(startedAt, now);
    }

    /**
     * The crash that ended {@code runner}'s current run, if one did. A crash
     * from before its last start belongs to an earlier run and does not count,
     * and a runner that is running has not crashed.
     */
    static Optional<LastCrash> currentCrash(ScriptRunner runner) {
        Instant started = runner.lastStartedAt();
        if (runner.isRunning() || started == null) {
            return Optional.empty();
        }
        return runner.health().lastCrash().filter(crash -> !crash.when().isBefore(started));
    }

    /** One line for a crash, e.g. "NullPointerException in onLoop()". */
    static String crashSummary(LastCrash crash) {
        String type = crash.cause() != null ? crash.cause().getClass().getSimpleName() : "Error";
        return type + " in " + phaseMethod(crash.phase());
    }

    private static String phaseMethod(Phase phase) {
        return switch (phase) {
            case ON_START -> "onStart()";
            case ON_LOOP -> "onLoop()";
            case ON_STOP -> "onStop()";
            case ON_CONFIG_UPDATE -> "onConfigUpdate()";
        };
    }
}
