package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Liveness;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What one client's runner of one script says about itself, read once. The
 * dot, the status line and the Start-on choice for that client are all derived
 * from this, so they cannot disagree.
 *
 * @param isClientAlive whether the client's pipe is up; a runner on a client that is
 *                      reconnecting or gone is shown as not connected, whatever it says
 * @param isRunning     the runner's own flag
 * @param liveness      the watchdog's verdict
 * @param lastStartedAt when the runner last started, or empty if it never has
 * @param lastCrash     the runner's most recent crash, from this run or an earlier one
 */
public record RunnerFacts(boolean isClientAlive, boolean isRunning, Liveness liveness,
                          Optional<Instant> lastStartedAt, Optional<LastCrash> lastCrash) {

    public RunnerFacts {
        Objects.requireNonNull(liveness, "liveness");
        Objects.requireNonNull(lastStartedAt, "lastStartedAt");
        Objects.requireNonNull(lastCrash, "lastCrash");
    }

    /**
     * Whether the client belongs in the script's row at all: it is running the
     * script, or has run it. Every client registers every installed script, so
     * a runner that was never started says nothing about where the script runs.
     */
    public boolean hasBeenUsed() {
        return isRunning || lastStartedAt.isPresent();
    }

    /**
     * The crash that ended the current run. A crash from before the last start
     * belongs to an earlier run that has since been restarted.
     */
    public Optional<LastCrash> currentCrash() {
        if (isRunning || lastStartedAt.isEmpty()) {
            return Optional.empty();
        }
        Instant started = lastStartedAt.get();
        return lastCrash.filter(crash -> !crash.when().isBefore(started));
    }
}
