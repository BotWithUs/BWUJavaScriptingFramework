package com.botwithus.bot.core.rpc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * Tracks RPC call statistics per method.
 *
 * <p>Each method has three {@link LongAdder} counters (call count, total
 * nanos, error count) plus a bounded {@link LatencyWindow} of recent samples
 * for percentile queries. The percentile window is intentionally an instance
 * field per-method, not static state, so each {@code RpcMetrics} instance
 * (one per {@code RpcClient}) keeps its own samples.</p>
 */
public class RpcMetrics {

    public record MethodStats(long callCount, long totalTimeNanos, long errorCount,
                              long[] percentileNanos, int[] percentiles) {
        public MethodStats {
            percentileNanos = percentileNanos.clone();
            percentiles = percentiles.clone();
        }

        public double avgLatencyMs() {
            return callCount > 0 ? (totalTimeNanos / 1_000_000.0) / callCount : 0;
        }

        /**
         * Returns the nanosecond value of the requested percentile, or 0 if the
         * percentile was not captured in the snapshot. The {@code percentiles}
         * array defines the available indices and is small (typically 2-3 entries).
         */
        public long percentileMs(int p) {
            for (int i = 0; i < percentiles.length; i++) {
                if (percentiles[i] == p) {
                    return percentileNanos[i] / 1_000_000L;
                }
            }
            return 0L;
        }
    }

    private static final int[] CAPTURED_PERCENTILES = {50, 95, 99};

    private static final class Entry {
        final LongAdder callCount = new LongAdder();
        final LongAdder totalTimeNanos = new LongAdder();
        final LongAdder errorCount = new LongAdder();
        final LatencyWindow window = new LatencyWindow();
    }

    private final ConcurrentHashMap<String, Entry> stats = new ConcurrentHashMap<>();

    public void recordCall(String method, long durationNanos, boolean error) {
        Entry e = stats.computeIfAbsent(method, k -> new Entry());
        e.callCount.increment();
        e.totalTimeNanos.add(durationNanos);
        if (error) {
            e.errorCount.increment();
        }
        e.window.record(durationNanos);
    }

    public Map<String, MethodStats> snapshot() {
        return stats.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> {
                    Entry v = e.getValue();
                    long[] pcts = v.window.percentiles(CAPTURED_PERCENTILES);
                    return new MethodStats(v.callCount.sum(), v.totalTimeNanos.sum(),
                            v.errorCount.sum(), pcts, CAPTURED_PERCENTILES);
                }
        ));
    }

    public void reset() {
        stats.clear();
    }
}
