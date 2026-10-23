package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.cli.gui.runners.RunnerReading;

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
 * @param reading       the runner's own report, and the shared rule for its status
 */
public record RunnerFacts(boolean isClientAlive, RunnerReading reading) {

    public RunnerFacts {
        Objects.requireNonNull(reading, "reading");
    }

    /**
     * Whether the client belongs in the script's row at all: it is running the
     * script, or has run it. Every client registers every installed script, so
     * a runner that was never started says nothing about where the script runs.
     */
    public boolean hasBeenUsed() {
        return reading.hasBeenStarted();
    }

    /** When the runner last started, or empty if it never has. */
    public Optional<Instant> lastStartedAt() {
        return reading.lastStartedAt();
    }

    /** The crash that ended the current run; see {@link RunnerReading#currentCrash()}. */
    public Optional<LastCrash> currentCrash() {
        return reading.currentCrash();
    }
}
