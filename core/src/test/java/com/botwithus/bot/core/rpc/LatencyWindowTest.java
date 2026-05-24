package com.botwithus.bot.core.rpc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LatencyWindowTest {

    @Test
    void emptyWindowReturnsZeros() {
        LatencyWindow w = new LatencyWindow();
        long[] pcts = w.percentiles(new int[]{50, 95});
        assertArrayEquals(new long[]{0L, 0L}, pcts);
        assertEquals(0, w.size());
    }

    @Test
    void recordsPercentilesOverFullWindow() {
        LatencyWindow w = new LatencyWindow(100);
        for (int i = 1; i <= 100; i++) {
            w.record(i);
        }
        assertEquals(100, w.size());
        assertEquals(50L, w.percentile(50));
        assertEquals(95L, w.percentile(95));
        assertEquals(99L, w.percentile(99));
        assertEquals(1L, w.percentile(0));
        assertEquals(100L, w.percentile(100));
    }

    @Test
    void ringOverwritesOldestSamples() {
        LatencyWindow w = new LatencyWindow(4);
        w.record(1);
        w.record(2);
        w.record(3);
        w.record(4);
        assertEquals(4, w.size());
        w.record(10);
        w.record(20);
        assertEquals(4, w.size());
        long[] pcts = w.percentiles(new int[]{0, 100});
        assertEquals(3L, pcts[0]);
        assertEquals(20L, pcts[1]);
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new LatencyWindow(0));
        assertThrows(IllegalArgumentException.class, () -> new LatencyWindow(-1));
    }

    @Test
    void rejectsPercentileOutOfRange() {
        LatencyWindow w = new LatencyWindow();
        w.record(100);
        assertThrows(IllegalArgumentException.class, () -> w.percentile(-1));
        assertThrows(IllegalArgumentException.class, () -> w.percentile(101));
    }

    @Test
    void resetClearsWindow() {
        LatencyWindow w = new LatencyWindow(8);
        w.record(1);
        w.record(2);
        w.reset();
        assertEquals(0, w.size());
        assertEquals(0L, w.percentile(50));
    }
}
