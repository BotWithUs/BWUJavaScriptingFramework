package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.cli.gui.runners.RunnerStatus;
import com.botwithus.bot.cli.gui.usermode.PulseLane;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One row of the script runners table: a script on one client, with its loop
 * timing from the runner's profiler.
 *
 * @param ref          what the row's buttons act on
 * @param clientLabel  the client as the user knows it: its account name, else its pipe
 * @param version      the manifest version, or blank
 * @param status       what the runner is doing
 * @param loops        loops since the last reset
 * @param avgMs        mean loop time since the last reset
 * @param lastMs       the most recent loop
 * @param maxMs        the slowest loop since the last reset
 * @param lane         the last loops in nanoseconds, oldest first, for the pulse lane
 * @param crashes      every crash this runner has had
 * @param stalledSince when the watchdog judged it stalled, if it is stalled and that was seen
 * @param hasSettings  whether the inspector has fields or a script UI to show for it
 */
public record RunnerRow(RunnerRef ref, String clientLabel, String version, ScriptCategory category,
                        RunnerStatus status, long loops, double avgMs, double lastMs, double maxMs,
                        long[] lane, long crashes, Optional<Instant> stalledSince, boolean hasSettings) {

    /** A slowest loop more than this multiple of the average is flagged. */
    public static final double MAX_OUTLIER_FACTOR = 5.0;

    public RunnerRow {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(stalledSince, "stalledSince");
        lane = lane.clone();
    }

    /** A copy of the recent loop times, oldest first. */
    @Override
    public long[] lane() {
        return lane.clone();
    }

    public String script() {
        return ref.script();
    }

    /** The worst loop is an outlier: more than {@link #MAX_OUTLIER_FACTOR} times the average. */
    public boolean isMaxOutlier() {
        return avgMs > 0 && maxMs > avgMs * MAX_OUTLIER_FACTOR;
    }

    /**
     * The last loop wants a look: the runner is stalled inside it, or it took
     * more than {@link PulseLane#SPIKE_FACTOR} times the average, the rule that
     * turns a pulse-lane bar amber.
     */
    public boolean isLastHot() {
        return status == RunnerStatus.STALLED || PulseLane.isSpike(lastMs, avgMs);
    }
}
