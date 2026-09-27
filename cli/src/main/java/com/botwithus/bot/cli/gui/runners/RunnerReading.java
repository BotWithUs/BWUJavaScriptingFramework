package com.botwithus.bot.cli.gui.runners;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Liveness;
import com.botwithus.bot.core.runtime.ScriptRunner;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What a script runner says about itself, read once, and the rule that turns it
 * into a {@link RunnerStatus}. Normal mode's cards, the Dashboard, the Groups
 * page and the Installed scripts page all derive a runner's state from here, so
 * they cannot disagree about it.
 *
 * @param isRunning     the runner's own flag; cleared as soon as a stop is asked for
 * @param liveness      the watchdog's verdict
 * @param lastStartedAt when the runner last started, or empty if it never has
 * @param lastCrash     the runner's most recent crash, from this run or an earlier one
 */
public record RunnerReading(boolean isRunning, Liveness liveness, Optional<Instant> lastStartedAt,
                            Optional<LastCrash> lastCrash) {

    public RunnerReading {
        Objects.requireNonNull(liveness, "liveness");
        Objects.requireNonNull(lastStartedAt, "lastStartedAt");
        Objects.requireNonNull(lastCrash, "lastCrash");
    }

    /**
     * Reads {@code runner} now. Its crash record is read only when it can decide
     * the status: while the runner runs, or before it ever started, no crash is
     * current, and {@link #lastCrash()} is left empty.
     */
    public static RunnerReading of(ScriptRunner runner) {
        boolean isRunning = runner.isRunning();
        Optional<Instant> started = Optional.ofNullable(runner.lastStartedAt());
        Optional<LastCrash> crash = isRunning || started.isEmpty()
                ? Optional.empty()
                : runner.health().lastCrash();
        return new RunnerReading(isRunning, runner.liveness(), started, crash);
    }

    /**
     * The runner's status. The watchdog's verdict comes first: a cut-off runner
     * can never run again and can still look busy, and a stalled one is stuck
     * whether or not a stop is pending. Only then does running decide, and last
     * whether the current run crashed.
     */
    public RunnerStatus status() {
        if (liveness.isTerminal()) {
            return RunnerStatus.CUT_OFF;
        }
        if (liveness == Liveness.STALLED) {
            return RunnerStatus.STALLED;
        }
        if (isRunning) {
            return RunnerStatus.RUNNING;
        }
        return currentCrash().isPresent() ? RunnerStatus.CRASHED : RunnerStatus.STOPPED;
    }

    /**
     * The crash that ended the current run. A runner is reused across restarts,
     * so a crash from before the latest start belongs to an earlier run and is
     * not current; neither is any crash while the runner runs.
     */
    public Optional<LastCrash> currentCrash() {
        if (isRunning || lastStartedAt.isEmpty()) {
            return Optional.empty();
        }
        Instant started = lastStartedAt.get();
        return lastCrash.filter(crash -> !crash.when().isBefore(started));
    }

    /**
     * Whether the runner has ever been used: it runs, or ran. The runtime
     * registers every installed script on every client, so a runner that never
     * started says nothing about where the script runs.
     */
    public boolean hasBeenStarted() {
        return isRunning || lastStartedAt.isPresent();
    }
}
