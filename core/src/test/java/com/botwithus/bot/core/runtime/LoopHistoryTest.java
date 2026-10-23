package com.botwithus.bot.core.runtime;

import org.junit.jupiter.api.Test;

import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoopHistoryTest {

    private static final int LANE = LoopHistory.DEFAULT_CAPACITY;

    @Test
    void snapshot_partiallyFilled_returnsOnlyRecordedOldestFirst() {
        LoopHistory history = new LoopHistory();
        history.record(10);
        history.record(20);
        history.record(30);

        assertArrayEquals(new long[] {10, 20, 30}, history.snapshot());
    }

    @Test
    void snapshot_afterWrapping_keepsLastTwentyFourInOrder() {
        LoopHistory history = new LoopHistory();
        int recorded = LANE + 7;
        for (long i = 1; i <= recorded; i++) {
            history.record(i);
        }

        long[] expected = LongStream.rangeClosed(recorded - LANE + 1, recorded).toArray();
        assertArrayEquals(expected, history.snapshot());
        assertEquals(LANE, history.size());
    }

    @Test
    void snapshot_exactlyFull_isNotRotated() {
        LoopHistory history = new LoopHistory();
        for (long i = 1; i <= LANE; i++) {
            history.record(i);
        }

        assertArrayEquals(LongStream.rangeClosed(1, LANE).toArray(), history.snapshot());
    }

    @Test
    void snapshot_isACopy() {
        LoopHistory history = new LoopHistory();
        history.record(5);
        long[] first = history.snapshot();
        first[0] = 99;

        assertEquals(5, history.snapshot()[0]);
    }

    @Test
    void clear_emptiesTheRing() {
        LoopHistory history = new LoopHistory();
        history.record(5);
        history.clear();
        history.record(6);

        assertArrayEquals(new long[] {6}, history.snapshot());
    }

    @Test
    void constructor_rejectsZeroCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new LoopHistory(0));
    }

    @Test
    void profiler_feedsHistoryAndResetClearsIt() {
        ScriptProfiler profiler = new ScriptProfiler();
        profiler.recordLoop(1_000_000);
        profiler.recordLoop(2_000_000);

        assertArrayEquals(new long[] {1_000_000, 2_000_000}, profiler.recentLoopNanos());

        profiler.reset();
        assertEquals(0, profiler.recentLoopNanos().length);
    }
}
