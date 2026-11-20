package com.botwithus.bot.api.util;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.PlayerStat;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link Stopwatch}, {@link Timer}, {@link Durations} and {@link XpTracker} on a fake clock. */
class TimingUtilitiesTest {

    private static final long SEED = 42;
    private static final int START_LEVEL = 50;

    /** Fake monotonic clock, moved only by {@link #advanceMs}. */
    private long nanos;

    private long now() {
        return nanos;
    }

    private void advanceMs(long ms) {
        nanos += TimeUnit.MILLISECONDS.toNanos(ms);
    }

    @Test
    void stopwatch_excludesPausedSpans() {
        Stopwatch watch = Stopwatch.started(this::now);
        advanceMs(1_000);
        watch.pause();
        advanceMs(5_000);
        assertEquals(1_000, watch.elapsedMs());
        watch.resume();
        advanceMs(500);
        assertEquals(1_500, watch.elapsedMs());
        assertTrue(watch.isRunning());
    }

    @Test
    void stopwatch_notStarted_readsZero() {
        Stopwatch watch = new Stopwatch(this::now);
        advanceMs(1_000);
        assertEquals(0, watch.elapsedMs());
        assertFalse(watch.isRunning());
    }

    @Test
    void stopwatch_reset_restartsFromZero() {
        Stopwatch watch = Stopwatch.started(this::now);
        advanceMs(1_000);
        watch.reset();
        advanceMs(200);
        assertEquals(200, watch.elapsedMs());
    }

    @Test
    void timer_drawsWithinBounds_andExpiresAfterItsDuration() {
        Timer timer = new Timer(1_000, 2_000, this::now, new SplittableRandom(SEED));
        long length = timer.durationMs();
        assertTrue(length >= 1_000 && length <= 2_000, "drawn " + length);
        assertFalse(timer.hasExpired());
        advanceMs(length - 1);
        assertEquals(1, timer.remainingMs());
        advanceMs(1);
        assertTrue(timer.hasExpired());
    }

    @Test
    void timer_reset_restartsTheCountdown() {
        Timer timer = new Timer(1_000, 1_000, this::now, new SplittableRandom(SEED));
        advanceMs(1_000);
        assertTrue(timer.hasExpired());
        timer.reset();
        assertFalse(timer.hasExpired());
        assertEquals(1_000, timer.remainingMs());
    }

    @Test
    void timer_badBounds_throw() {
        assertThrows(IllegalArgumentException.class, () -> new Timer(2_000, 1_000));
        assertThrows(IllegalArgumentException.class, () -> new Timer(-1, 1_000));
    }

    @Test
    void durations_format() {
        assertEquals("00:00:00", Durations.format(Duration.ZERO));
        assertEquals("01:02:03", Durations.format(Duration.ofSeconds(3_723)));
        assertEquals("2d 03:00:00", Durations.format(Duration.ofHours(51)));
        assertEquals("00:00:00", Durations.format(Duration.ofSeconds(-5)));
        assertEquals("00:01:00", Durations.formatMs(60_999));
    }

    @Test
    void durations_perHour() {
        assertEquals(1_200, Durations.perHour(600, Duration.ofMinutes(30)));
        assertEquals(0, Durations.perHour(600, Duration.ZERO));
    }

    @Test
    void xpTracker_reportsGainRateLevelsAndTimeToNext() {
        GameAPI api = mock(GameAPI.class);
        int start = XpTable.STANDARD.xpForLevel(START_LEVEL);
        int gained = 20_000;
        when(api.getPlayerStat(Skills.MINING)).thenReturn(stat(start));
        XpTracker tracker = new XpTracker(api, SkillType.MINING, Stopwatch.started(this::now));

        advanceMs(Duration.ofMinutes(30).toMillis());
        when(api.getPlayerStat(Skills.MINING)).thenReturn(stat(start + gained));

        assertEquals(gained, tracker.xpGained());
        assertEquals(2L * gained, tracker.xpPerHour());
        int expectedLevel = XpTable.STANDARD.levelForXp(start + gained);
        assertTrue(expectedLevel > START_LEVEL, "fixture must cross a level");
        assertEquals(expectedLevel - START_LEVEL, tracker.levelsGained());
        int remaining = XpTable.STANDARD.xpToNextLevel(start + gained);
        long expectedMs = remaining * Duration.ofHours(1).toMillis() / (2L * gained);
        assertEquals(Optional.of(Duration.ofMillis(expectedMs)), tracker.timeToNextLevel());
    }

    @Test
    void xpTracker_noStatAtStart_takesTheBaselineOnFirstRead() {
        GameAPI api = mock(GameAPI.class);
        XpTracker tracker = new XpTracker(api, SkillType.MINING, Stopwatch.started(this::now));
        advanceMs(Duration.ofHours(1).toMillis());
        when(api.getPlayerStat(Skills.MINING)).thenReturn(stat(1_000));

        assertEquals(0, tracker.xpGained());
        assertEquals(Duration.ZERO, tracker.elapsed(), "clock restarts at the baseline");
        assertEquals(Optional.empty(), tracker.timeToNextLevel());
    }

    private static PlayerStat stat(int xp) {
        int level = XpTable.STANDARD.levelForXp(xp);
        return new PlayerStat(Skills.MINING, level, level, xp);
    }
}
