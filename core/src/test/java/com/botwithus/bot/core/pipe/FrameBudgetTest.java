package com.botwithus.bot.core.pipe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The stream pipe's throughput guard. A frame is self-limiting on its own; the
 * budget is what stops a producer sending them back to back forever.
 */
class FrameBudgetTest {

    private static final int MAX_FRAMES = 10;
    private static final long MAX_BYTES = 1000L;
    private static final long WINDOW_NANOS = 1_000_000_000L;
    private static final int SMALL_FRAME = 1;

    /** Manually advanced clock, so the tests never sleep. */
    private final AtomicLong now = new AtomicLong();

    private FrameBudget newBudget() {
        return new FrameBudget(MAX_FRAMES, MAX_BYTES, WINDOW_NANOS, now::get);
    }

    @Test
    @DisplayName("traffic inside both limits is admitted")
    void record_withinBothLimits_admitsFrame() {
        FrameBudget budget = newBudget();
        for (int i = 0; i < MAX_FRAMES; i++) {
            assertEquals(FrameBudget.Verdict.WITHIN_BUDGET, budget.record(SMALL_FRAME),
                    "frame " + i + " is inside both limits");
        }
    }

    @Test
    @DisplayName("a flood of tiny frames trips the frame-count limit")
    void record_tooManySmallFrames_reportsFrameRateExceeded() {
        FrameBudget budget = newBudget();
        for (int i = 0; i < MAX_FRAMES; i++) {
            budget.record(SMALL_FRAME);
        }
        // Nowhere near the byte limit — frame count is the binding constraint.
        assertEquals(FrameBudget.Verdict.FRAME_RATE_EXCEEDED, budget.record(SMALL_FRAME));
    }

    @Test
    @DisplayName("a few large frames trip the byte limit before the frame limit")
    void record_tooManyBytes_reportsByteRateExceeded() {
        FrameBudget budget = newBudget();
        int halfBudget = (int) (MAX_BYTES / 2);
        assertEquals(FrameBudget.Verdict.WITHIN_BUDGET, budget.record(halfBudget));
        assertEquals(FrameBudget.Verdict.WITHIN_BUDGET, budget.record(halfBudget));
        // Third frame is only the 3rd of 10 allowed, so this can only be bytes.
        assertEquals(FrameBudget.Verdict.BYTE_RATE_EXCEEDED, budget.record(halfBudget));
    }

    @Test
    @DisplayName("the window rolls over, so a slow producer is never throttled")
    void record_afterWindowElapses_admitsFrameAgain() {
        FrameBudget budget = newBudget();
        for (int i = 0; i < MAX_FRAMES; i++) {
            budget.record(SMALL_FRAME);
        }
        assertEquals(FrameBudget.Verdict.FRAME_RATE_EXCEEDED, budget.record(SMALL_FRAME));

        now.addAndGet(WINDOW_NANOS);
        assertEquals(FrameBudget.Verdict.WITHIN_BUDGET, budget.record(SMALL_FRAME),
                "a fresh window starts the counters over");
    }

    @Test
    @DisplayName("the window boundary is inclusive — exactly one window elapsed rolls over")
    void record_exactlyAtWindowBoundary_rollsOver() {
        FrameBudget budget = newBudget();
        budget.record((int) MAX_BYTES);
        now.addAndGet(WINDOW_NANOS);
        assertEquals(FrameBudget.Verdict.WITHIN_BUDGET, budget.record((int) MAX_BYTES));
    }

    @Test
    @DisplayName("a frame arriving just inside the window still charges the old one")
    void record_justBeforeWindowElapses_keepsCharging() {
        FrameBudget budget = newBudget();
        budget.record((int) MAX_BYTES);
        now.addAndGet(WINDOW_NANOS - 1);
        assertEquals(FrameBudget.Verdict.BYTE_RATE_EXCEEDED, budget.record(SMALL_FRAME));
    }

    @Test
    @DisplayName("budget accounting survives a nanoTime wrap")
    void record_acrossNanoTimeWrap_stillRollsOver() {
        now.set(Long.MAX_VALUE - WINDOW_NANOS / 2);
        FrameBudget budget = newBudget();
        for (int i = 0; i < MAX_FRAMES; i++) {
            budget.record(SMALL_FRAME);
        }
        assertEquals(FrameBudget.Verdict.FRAME_RATE_EXCEEDED, budget.record(SMALL_FRAME));

        // Crossing the signed-long boundary: the elapsed check subtracts rather
        // than compares, so the window still rolls over here.
        now.addAndGet(WINDOW_NANOS);
        assertEquals(FrameBudget.Verdict.WITHIN_BUDGET, budget.record(SMALL_FRAME));
    }
}
