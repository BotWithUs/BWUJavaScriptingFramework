package com.botwithus.bot.core.rpc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * Tracks RPC call statistics per method.
 */
public class RpcMetrics {

    public record MethodStats(long callCount, long totalTimeNanos, long errorCount) {
        public double avgLatencyMs() {
            return callCount > 0 ? (totalTimeNanos / 1_000_000.0) / callCount : 0;
        }
    }

    // index 0 = callCount, 1 = totalTimeNanos, 2 = errorCount
    private final ConcurrentHashMap<String, LongAdder[]> stats = new ConcurrentHashMap<>();

    public void recordCall(String method, long durationNanos, boolean error) {
        LongAdder[] adders = stats.computeIfAbsent(method, k -> new LongAdder[]{
                new LongAdder(), new LongAdder(), new LongAdder()
        });
        adders[0].increment();
        adders[1].add(durationNanos);
        if (error) adders[2].increment();
    }

    public Map<String, MethodStats> snapshot() {
        return stats.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> {
                    LongAdder[] a = e.getValue();
                    return new MethodStats(a[0].sum(), a[1].sum(), a[2].sum());
                }
        ));
    }

    public void reset() {
        stats.clear();
    }
}
