package com.botwithus.bot.api.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link ConditionWait} on a fake clock: sleeping advances the clock, and the server
 * tick is derived from it, so no test here sleeps for real.
 */
class ConditionWaitTest {

    private static final long TIMEOUT_MS = 1_000;

    /** Fake monotonic clock; {@link #sleep} is the only thing that moves it. */
    private static final class FakeClock {
        private long nanos;
        private int sleeps;
        private boolean isTickStalled;

        long nanoTime() {
            return nanos;
        }

        void sleep(long ms) {
            nanos += TimeUnit.MILLISECONDS.toNanos(ms);
            sleeps++;
        }

        long elapsedMs() {
            return TimeUnit.NANOSECONDS.toMillis(nanos);
        }

        int serverTick() {
            return isTickStalled ? ConditionWait.NO_TICK : (int) (elapsedMs() / Timing.TICK_MS);
        }
    }

    private final FakeClock clock = new FakeClock();
    private final AtomicBoolean isStopRequested = new AtomicBoolean();
    private final ConditionWait wait =
            new ConditionWait(clock::nanoTime, clock::sleep, isStopRequested::get);

    @Test
    void until_conditionBecomesTrue_returnsTrueAtThatPoll() {
        AtomicInteger checks = new AtomicInteger();
        assertTrue(wait.until(() -> checks.incrementAndGet() == 3, TIMEOUT_MS));
        assertEquals(3, checks.get());
        assertEquals(2 * ConditionWait.POLL_INTERVAL_MS, clock.elapsedMs());
    }

    @Test
    void until_conditionNeverTrue_returnsFalseAtTheTimeout() {
        assertFalse(wait.until(() -> false, TIMEOUT_MS));
        assertEquals(TIMEOUT_MS, clock.elapsedMs());
    }

    @Test
    void until_zeroTimeout_checksOnceWithoutSleeping() {
        assertTrue(wait.until(() -> true, 0));
        assertFalse(wait.until(() -> false, 0));
        assertEquals(0, clock.sleeps);
    }

    @Test
    void until_stopRequestedMidWait_returnsFalseWithinOnePoll() {
        AtomicInteger checks = new AtomicInteger();
        boolean met = wait.until(() -> {
            if (checks.incrementAndGet() == 2) {
                isStopRequested.set(true);
            }
            return false;
        }, TIMEOUT_MS);

        assertFalse(met);
        assertEquals(2, checks.get(), "no further poll after the stop request");
        assertTrue(clock.elapsedMs() < TIMEOUT_MS);
    }

    @Test
    void until_stopAlreadyRequested_neverChecksTheCondition() {
        isStopRequested.set(true);
        AtomicInteger checks = new AtomicInteger();
        assertFalse(wait.until(() -> checks.incrementAndGet() > 0, TIMEOUT_MS));
        assertEquals(0, checks.get());
    }

    @Test
    void untilTicks_conditionMetOnTickThree_returnsTrue() {
        assertTrue(wait.untilTicks(clock::serverTick, () -> clock.serverTick() >= 3, 5));
        assertEquals(3, clock.serverTick());
    }

    @Test
    void untilTicks_conditionNeverMet_endsAfterMaxServerTicks() {
        assertFalse(wait.untilTicks(clock::serverTick, () -> false, 4));
        assertEquals(4, clock.serverTick());
    }

    @Test
    void untilTicks_stalledTickClock_endsAtTheWallClockCap() {
        clock.isTickStalled = true;
        int maxTicks = 3;

        assertFalse(wait.untilTicks(clock::serverTick, () -> false, maxTicks));

        long cap = Timing.ticksToMs(maxTicks) * ConditionWait.STALLED_CLOCK_FACTOR;
        assertEquals(cap, clock.elapsedMs());
    }

    @Test
    void ticks_waitsExactlyThatManyServerTicks() {
        assertTrue(wait.ticks(clock::serverTick, 2));
        assertEquals(2, clock.serverTick());
    }

    @Test
    void ticks_stopRequested_returnsFalse() {
        isStopRequested.set(true);
        assertFalse(wait.ticks(clock::serverTick, 2));
        assertEquals(0, clock.sleeps);
    }

    @Test
    void timingDelayWhile_onInterruptedThread_returnsFalseImmediately() {
        Thread.currentThread().interrupt();
        try {
            long start = System.nanoTime();
            assertFalse(Timing.delayWhile(() -> true, TIMEOUT_MS));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < TIMEOUT_MS);
        } finally {
            Thread.interrupted();
        }
    }
}
