package com.botwithus.bot.core.rpc;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Tracks RPC call statistics per method.
 *
 * <p>Each method has three {@link LongAdder} counters (call count, total
 * nanos, error count) plus a bounded {@link LatencyWindow} of recent samples
 * for percentile queries. The percentile window is intentionally an instance
 * field per-method, not static state, so each {@code RpcMetrics} instance
 * (one per {@code RpcClient}) keeps its own samples.</p>
 *
 * <p>{@link #pooled} combines several instances, so a host with many clients
 * can show one table whose percentiles are taken over every client's samples
 * together. {@link #setCollecting} switches the bookkeeping off and on.</p>
 */
public class RpcMetrics {

    public RpcMetrics() {}

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

        /**
         * The requested percentile in nanoseconds, or 0 if it was not captured.
         * Unlike {@link #percentileMs}, this keeps the part of a millisecond that
         * a sub-millisecond call is made of.
         */
        public long percentileNanos(int p) {
            for (int i = 0; i < percentiles.length; i++) {
                if (percentiles[i] == p) {
                    return percentileNanos[i];
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
    private volatile BooleanSupplier collecting = () -> true;

    /**
     * Decides, call by call, whether {@link #recordCall} keeps anything. Read on
     * every recorded call, so it must be cheap and thread-safe; a volatile flag
     * behind a lambda is the intended shape. Samples already kept stay.
     */
    public void setCollecting(BooleanSupplier gate) {
        this.collecting = gate != null ? gate : () -> true;
    }

    public void recordCall(String method, long durationNanos, boolean error) {
        if (!collecting.getAsBoolean()) {
            return;
        }
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

    /**
     * Per-method stats over several sources taken together: counts and totals
     * are summed, and the percentiles are computed over the union of every
     * source's recent samples, never averaged from each source's own
     * percentiles. A method only some sources called is still included.
     */
    public static Map<String, MethodStats> pooled(Collection<RpcMetrics> sources) {
        Map<String, Pool> pools = new HashMap<>();
        for (RpcMetrics source : sources) {
            source.stats.forEach((method, entry) ->
                    pools.computeIfAbsent(method, k -> new Pool()).add(entry));
        }
        Map<String, MethodStats> out = new HashMap<>();
        pools.forEach((method, pool) -> out.put(method, pool.toStats()));
        return Map.copyOf(out);
    }

    /** One method's figures while {@link #pooled} is adding sources up. */
    private static final class Pool {
        private long calls;
        private long totalNanos;
        private long errors;
        private long[] samples = new long[0];

        void add(Entry entry) {
            calls += entry.callCount.sum();
            totalNanos += entry.totalTimeNanos.sum();
            errors += entry.errorCount.sum();
            long[] more = entry.window.samples();
            long[] joined = Arrays.copyOf(samples, samples.length + more.length);
            System.arraycopy(more, 0, joined, samples.length, more.length);
            samples = joined;
        }

        MethodStats toStats() {
            return new MethodStats(calls, totalNanos, errors,
                    LatencyWindow.percentilesOf(samples, CAPTURED_PERCENTILES), CAPTURED_PERCENTILES);
        }
    }

    public void reset() {
        stats.clear();
    }
}
