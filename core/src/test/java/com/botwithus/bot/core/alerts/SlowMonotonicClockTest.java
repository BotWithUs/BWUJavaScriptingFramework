package com.botwithus.bot.core.alerts;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlowMonotonicClockTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    private static final int READS = 10_000;
    private static final long NAP_MS = 20;
    /** {@code instant()} floors each read, so two reads can differ from the exact ratio by one. */
    private static final long FLOOR_SLACK_NANOS = 1;
    private static final ZoneId OTHER_ZONE = ZoneId.of("Europe/London");

    private final SlowMonotonicClock clock = SlowMonotonicClock.startingAt(BASE);

    @Test
    void startingAt_defaultsToHalfSpeed() {
        assertEquals(2, clock.slowdown());
    }

    @Test
    void instant_neverGoesBackwards() {
        Instant previous = clock.instant();
        for (int i = 0; i < READS; i++) {
            Instant next = clock.instant();
            assertFalse(next.isBefore(previous), "went back from " + previous + " to " + next);
            previous = next;
        }
    }

    @Test
    void instant_runsAtTheConfiguredFractionOfRealTime() throws InterruptedException {
        long realStart = System.nanoTime();
        Instant clockStart = clock.instant();
        Thread.sleep(NAP_MS);
        Instant clockEnd = clock.instant();
        long realElapsed = System.nanoTime() - realStart;

        long clockElapsed = Duration.between(clockStart, clockEnd).toNanos();
        assertAll(
                () -> assertTrue(clockElapsed > 0, "the clock did not move"),
                () -> assertTrue(clockElapsed <= realElapsed / clock.slowdown() + FLOOR_SLACK_NANOS,
                        () -> "clock moved " + clockElapsed + "ns in " + realElapsed + "ns real"));
    }

    @Test
    void withZone_changesTheZoneButKeepsTheTimeline() {
        Instant before = clock.instant();
        var zoned = clock.withZone(OTHER_ZONE);
        Instant during = zoned.instant();
        Instant after = clock.instant();

        assertAll(
                () -> assertEquals(OTHER_ZONE, zoned.getZone()),
                () -> assertFalse(during.isBefore(before)),
                () -> assertFalse(after.isBefore(during)),
                () -> assertSame(clock, clock.withZone(clock.getZone())));
    }

    @Test
    void startingAt_aSlowdownThatIsNotSlower_isRefused() {
        assertThrows(IllegalArgumentException.class, () -> SlowMonotonicClock.startingAt(BASE, 1));
    }
}
