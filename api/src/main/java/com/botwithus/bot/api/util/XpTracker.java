package com.botwithus.bot.api.util;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.PlayerStat;

import java.time.Duration;
import java.util.Optional;

/**
 * Xp progress in one skill since the tracker started: xp gained, xp per hour, levels gained
 * and the projected time to the next level. Reads {@link GameAPI#getPlayerStat(int)} on each
 * call, so it is always current and holds no game state of its own. Not thread-safe.
 *
 * <pre>{@code
 * XpTracker wc = new XpTracker(api, SkillType.WOODCUTTING);
 * ...
 * ui.text("xp/hr " + wc.xpPerHour() + ", next level in "
 *         + wc.timeToNextLevel().map(Durations::format).orElse("--"));
 * }</pre>
 *
 * <p>The baseline is taken when the tracker is made or, if the stat is not readable then
 * (not logged in yet), at the first call that can read it. The clock restarts at that moment,
 * so time spent before a baseline existed never dilutes the rate.</p>
 */
public final class XpTracker {

    private final GameAPI api;
    private final SkillType skill;
    private final Stopwatch stopwatch;
    private PlayerStat baseline;

    /** Starts tracking {@code skill} now. */
    public XpTracker(GameAPI api, SkillType skill) {
        this(api, skill, Stopwatch.started());
    }

    /** For tests: tracks on the given (running) stopwatch. */
    XpTracker(GameAPI api, SkillType skill, Stopwatch stopwatch) {
        this.api = api;
        this.skill = skill;
        this.stopwatch = stopwatch;
        this.baseline = api.getPlayerStat(skill.id());
    }

    /** The skill being tracked. */
    public SkillType skill() {
        return skill;
    }

    /** Time since the baseline was taken. */
    public Duration elapsed() {
        current();
        return stopwatch.elapsed();
    }

    /** Xp gained since the baseline; {@code 0} before one exists. */
    public int xpGained() {
        PlayerStat now = current();
        return now == null || baseline == null ? 0 : now.experience() - baseline.experience();
    }

    /** Base levels gained since the baseline; {@code 0} before one exists. */
    public int levelsGained() {
        PlayerStat now = current();
        return now == null || baseline == null ? 0 : now.level() - baseline.level();
    }

    /** {@link #xpGained()} as an hourly rate over {@link #elapsed()}. */
    public long xpPerHour() {
        return Durations.perHour(xpGained(), elapsed());
    }

    /**
     * Projected time to the next (virtual) level at the current rate. Empty when no xp has
     * been gained yet, the stat is not readable, or the skill is at the top of its curve.
     */
    public Optional<Duration> timeToNextLevel() {
        PlayerStat now = current();
        long rate = xpPerHour();
        if (now == null || rate <= 0) {
            return Optional.empty();
        }
        int remaining = skill.xpTable().xpToNextLevel(now.experience());
        if (remaining == 0) {
            return Optional.empty();
        }
        long hourMs = Duration.ofHours(1).toMillis();
        return Optional.of(Duration.ofMillis(remaining * hourMs / rate));
    }

    /** Takes a new baseline now and restarts the clock. */
    public void reset() {
        baseline = api.getPlayerStat(skill.id());
        stopwatch.reset();
    }

    /** Reads the stat, taking the baseline first if there was none. */
    private PlayerStat current() {
        PlayerStat now = api.getPlayerStat(skill.id());
        if (baseline == null && now != null) {
            baseline = now;
            stopwatch.reset();
        }
        return now;
    }
}
