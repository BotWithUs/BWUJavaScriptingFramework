package com.botwithus.bot.core.rpc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RpcMetricsTest {

    @Test
    void recordCallAndSnapshot() {
        RpcMetrics metrics = new RpcMetrics();
        metrics.recordCall("test.method", 1_000_000, false); // 1ms
        metrics.recordCall("test.method", 2_000_000, false); // 2ms

        var snapshot = metrics.snapshot();
        assertEquals(1, snapshot.size());

        RpcMetrics.MethodStats stats = snapshot.get("test.method");
        assertEquals(2, stats.callCount());
        assertEquals(3_000_000, stats.totalTimeNanos());
        assertEquals(0, stats.errorCount());
        assertEquals(1.5, stats.avgLatencyMs(), 0.01);
    }

    @Test
    void recordErrors() {
        RpcMetrics metrics = new RpcMetrics();
        metrics.recordCall("test.method", 1_000_000, true);
        metrics.recordCall("test.method", 1_000_000, false);

        var stats = metrics.snapshot().get("test.method");
        assertEquals(2, stats.callCount());
        assertEquals(1, stats.errorCount());
    }

    @Test
    void reset() {
        RpcMetrics metrics = new RpcMetrics();
        metrics.recordCall("test.method", 1_000_000, false);
        assertFalse(metrics.snapshot().isEmpty());

        metrics.reset();
        assertTrue(metrics.snapshot().isEmpty());
    }

    @Test
    void avgLatencyWithZeroCalls() {
        RpcMetrics.MethodStats stats = new RpcMetrics.MethodStats(0, 0, 0,
                new long[]{0L, 0L, 0L}, new int[]{50, 95, 99});
        assertEquals(0, stats.avgLatencyMs());
    }

    @Test
    void multipleMethods() {
        RpcMetrics metrics = new RpcMetrics();
        metrics.recordCall("method.a", 1_000_000, false);
        metrics.recordCall("method.b", 2_000_000, false);

        var snapshot = metrics.snapshot();
        assertEquals(2, snapshot.size());
        assertNotNull(snapshot.get("method.a"));
        assertNotNull(snapshot.get("method.b"));
    }

    @Test
    void capturedPercentilesReflectRecentSamples() {
        RpcMetrics metrics = new RpcMetrics();
        // 1ms, 2ms, ..., 100ms — p50 = 50ms, p95 = 95ms, p99 = 99ms (within tolerance)
        for (int i = 1; i <= 100; i++) {
            metrics.recordCall("perf.method", i * 1_000_000L, false);
        }
        RpcMetrics.MethodStats stats = metrics.snapshot().get("perf.method");
        assertEquals(50, stats.percentileMs(50));
        assertEquals(95, stats.percentileMs(95));
        assertEquals(99, stats.percentileMs(99));
    }

    @Test
    void percentilesEmptyForUnknownPercentile() {
        RpcMetrics metrics = new RpcMetrics();
        metrics.recordCall("perf.method", 1_000_000L, false);
        RpcMetrics.MethodStats stats = metrics.snapshot().get("perf.method");
        assertEquals(0L, stats.percentileMs(75));
    }
}
