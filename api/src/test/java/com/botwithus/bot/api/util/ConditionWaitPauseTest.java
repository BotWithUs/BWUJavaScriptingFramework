package com.botwithus.bot.api.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pausing inside a condition wait, on a fake clock. The pause lasts 2.5 s against a 1 s
 * timeout and a 3-tick budget, so a wait that kept spending its budget while paused would
 * have ended (false) before the pause did.
 */
class ConditionWaitPauseTest {

    private static final long TIMEOUT_MS = 1_000;
    private static final long PAUSE_MS = 2_500;

    private long nanos;
    /** Wall time at which the pause ends; the pause starts at 0. */
    private long pausedUntilMs = PAUSE_MS;
    private final AtomicBoolean isStopRequested = new AtomicBoolean();
    private final ConditionWait wait = new ConditionWait(() -> nanos,
            ms -> nanos += TimeUnit.MILLISECONDS.toNanos(ms), isStopRequested::get,
            () -> elapsedMs() < pausedUntilMs);

    private long elapsedMs() {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private int serverTick() {
        return (int) (elapsedMs() / Timing.TICK_MS);
    }

    @Test
    void until_doesNotTestTheCondition_orSpendTheTimeout_whilePaused() {
        AtomicInteger checks = new AtomicInteger();

        boolean met = wait.until(() -> checks.incrementAndGet() == 3, TIMEOUT_MS);

        assertTrue(met);
        assertEquals(3, checks.get());
        assertEquals(PAUSE_MS + 2 * ConditionWait.POLL_INTERVAL_MS, elapsedMs());
    }

    @Test
    void until_timesOutOnlyAfterTheFullTimeoutOfUnpausedTime() {
        assertFalse(wait.until(() -> false, TIMEOUT_MS));
        assertEquals(PAUSE_MS + TIMEOUT_MS, elapsedMs());
    }

    @Test
    void ticks_doNotCountTicksThatPassWhilePaused() {
        assertTrue(wait.ticks(this::serverTick, 3));
        // The pause covers ticks 0..4; three more must pass after it ends.
        assertEquals(PAUSE_MS / Timing.TICK_MS + 3, serverTick());
    }

    @Test
    void stopWhilePaused_endsTheWait_withoutTestingTheCondition() {
        AtomicInteger checks = new AtomicInteger();
        ConditionWait stopping = new ConditionWait(() -> nanos, ms -> {
            nanos += TimeUnit.MILLISECONDS.toNanos(ms);
            isStopRequested.set(elapsedMs() >= TIMEOUT_MS);
        }, isStopRequested::get, () -> true);

        assertFalse(stopping.until(() -> checks.incrementAndGet() > 0, TIMEOUT_MS));
        assertEquals(0, checks.get());
        assertEquals(TIMEOUT_MS, elapsedMs());
    }
}
