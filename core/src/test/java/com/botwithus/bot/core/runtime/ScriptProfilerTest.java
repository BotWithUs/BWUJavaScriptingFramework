package com.botwithus.bot.core.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ScriptProfilerTest {

    @Test
    void recordAndStats() {
        ScriptProfiler profiler = new ScriptProfiler();
        profiler.recordLoop(1_000_000); // 1ms
        profiler.recordLoop(3_000_000); // 3ms

        assertEquals(2, profiler.getLoopCount());
        assertEquals(4_000_000, profiler.getTotalLoopTimeNanos());
        assertEquals(2.0, profiler.avgLoopMs(), 0.01);
        assertEquals(1_000_000, profiler.getMinLoopNanos());
        assertEquals(3_000_000, profiler.getMaxLoopNanos());
        assertEquals(3_000_000, profiler.getLastLoopNanos());
    }

    @Test
    void reset() {
        ScriptProfiler profiler = new ScriptProfiler();
        profiler.recordLoop(1_000_000);
        assertTrue(profiler.getLoopCount() > 0);

        profiler.reset();
        assertEquals(0, profiler.getLoopCount());
        assertEquals(0, profiler.getTotalLoopTimeNanos());
        assertEquals(0, profiler.getMinLoopNanos());
        assertEquals(0, profiler.getMaxLoopNanos());
        assertEquals(0, profiler.getLastLoopNanos());
        assertEquals(0.0, profiler.avgLoopMs());
    }

    @Test
    void avgWithZeroLoops() {
        ScriptProfiler profiler = new ScriptProfiler();
        assertEquals(0.0, profiler.avgLoopMs());
    }

    @Test
    void recordLoop_withAggregatesOff_stillFeedsTheLaneAndLast_butNoAggregates() {
        ScriptProfiler profiler = new ScriptProfiler();
        AtomicBoolean aggregating = new AtomicBoolean(false);
        profiler.setAggregating(aggregating::get);

        profiler.recordLoop(2_000_000);

        assertAll(
                () -> assertArrayEquals(new long[]{2_000_000}, profiler.recentLoopNanos()),
                () -> assertEquals(2_000_000, profiler.getLastLoopNanos()),
                () -> assertEquals(0, profiler.getLoopCount()),
                () -> assertEquals(0, profiler.getTotalLoopTimeNanos()),
                () -> assertEquals(0, profiler.getMaxLoopNanos()));

        aggregating.set(true);
        profiler.recordLoop(4_000_000);
        assertEquals(1, profiler.getLoopCount());
        assertEquals(4_000_000, profiler.getMaxLoopNanos());
    }
}
