package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.cli.gui.runners.CrashText;
import com.botwithus.bot.cli.gui.runners.RunnerReading;
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
     * What {@code runner}'s row shows: its {@link RunnerReading#status()}, with
     * the time spent stalled, how long it has run or when it crashed.
     *
     * @param nowNanos the {@link System#nanoTime()} to measure a stall against
     * @param zone     the zone a crash's time is shown in
     */
    static ScriptState stateOf(ScriptRunner runner, Instant now, long nowNanos, ZoneId zone) {
        RunnerReading reading = RunnerReading.of(runner);
        return switch (reading.status()) {
            case CUT_OFF -> new ScriptState.CutOff();
            case STALLED -> new ScriptState.Stalled(
                    Duration.ofMillis(Math.max(0L, runner.livenessState().millisInLoop(nowNanos))));
            case RUNNING -> new ScriptState.Running(runningFor(runner.lastStartedAt(), now));
            case CRASHED, STOPPED -> reading.currentCrash()
                    .<ScriptState>map(crash -> new ScriptState.Crashed(CrashText.summary(crash),
                            LocalTime.ofInstant(crash.when(), zone)))
                    .orElseGet(ScriptState.Stopped::new);
        };
    }

    private static Duration runningFor(Instant startedAt, Instant now) {
        if (startedAt == null || now.isBefore(startedAt)) {
            return Duration.ZERO;
        }
        return Duration.between(startedAt, now);
    }

}
