package com.botwithus.bot.api.util;

import com.botwithus.bot.api.runtime.SessionPacing;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Humanize} steps back while the agent's session layer owns action timing. */
class HumanizeSessionPacingTest {

    private static final int BASE_MS = 1_000;
    private static final double VARIANCE = 0.3;
    private static final int SAMPLES = 2_000;
    private static final long HELD_PRESS_MS = 1_234;
    /** Well above any scheduling noise, well below the inactive sleep's 1 s mean. */
    private static final long NO_SLEEP_BOUND_MS = 200;
    /** Past ~6.7 SD of the 1 s gaussian: only a micro-break (2-5 s added) gets here. */
    private static final int MICRO_BREAK_ONLY_MS = 3_000;

    private static Supplier<SessionPacing> active(long nextPressInMs) {
        return () -> new SessionPacing(true, nextPressInMs, Optional.empty());
    }

    private static <T> T with(Supplier<SessionPacing> pacing, Supplier<T> body) {
        return ScopedValue.where(SessionPacing.CURRENT, pacing).call(body::get);
    }

    @Test
    void loopDelay_layerActiveWithHeldAction_returnsTheRemainingPlannedGap() {
        for (int i = 0; i < SAMPLES; i++) {
            int delay = with(active(HELD_PRESS_MS), () -> Humanize.loopDelay(BASE_MS, VARIANCE));
            assertEquals(HELD_PRESS_MS, delay, "no jitter and no micro-break on top of the plan");
        }
    }

    @Test
    void loopDelay_layerActiveNothingHeld_returnsTheMinimumPoll() {
        int delay = with(active(0), () -> Humanize.loopDelay(BASE_MS, VARIANCE));

        assertEquals(Humanize.MIN_DEFERRED_LOOP_DELAY_MS, delay);
    }

    @Test
    void loopDelay_layerInactive_keepsTheOldDistribution() {
        Supplier<SessionPacing> inactive = () -> SessionPacing.INACTIVE;
        for (int i = 0; i < SAMPLES; i++) {
            int delay = with(inactive, () -> Humanize.loopDelay(BASE_MS, VARIANCE));
            assertTrue(delay >= BASE_MS / 2, "floor is half the base: " + delay);
        }
    }

    @Test
    void loopDelay_offAScriptThread_keepsTheOldBehaviour() {
        int microBreaks = 0;
        for (int i = 0; i < SAMPLES; i++) {
            int delay = Humanize.loopDelay(BASE_MS, VARIANCE);
            assertTrue(delay >= BASE_MS / 2, "floor is half the base: " + delay);
            if (delay > MICRO_BREAK_ONLY_MS) {
                microBreaks++;
            }
        }
        assertTrue(microBreaks > 0, "the 5% micro-break still fires with no layer bound");
    }

    @Test
    void loopDelay_readsThePlanOncePerCall() {
        AtomicInteger reads = new AtomicInteger();
        Supplier<SessionPacing> counting = () -> {
            reads.incrementAndGet();
            return SessionPacing.INACTIVE;
        };

        with(counting, () -> Humanize.loopDelay(BASE_MS, VARIANCE));

        assertEquals(1, reads.get());
    }

    @Test
    void sleep_layerActiveNothingHeld_returnsAtOnce() {
        long start = System.nanoTime();

        with(active(0), () -> {
            Humanize.sleep(BASE_MS, BASE_MS / 10);
            return null;
        });

        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(tookMs < NO_SLEEP_BOUND_MS, "slept " + tookMs + " ms instead of deferring");
    }
}
