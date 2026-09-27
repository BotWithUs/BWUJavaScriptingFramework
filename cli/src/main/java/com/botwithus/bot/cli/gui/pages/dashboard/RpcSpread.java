package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.core.rpc.RpcMetrics;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The RPC latency table: every client's metrics pooled per method, busiest
 * method first. Pooling sorts every client's recent samples, which is too much
 * to do every frame with a dozen clients, so a table is reused for up to
 * {@link #REFRESH} for the same scope. Render thread only.
 */
final class RpcSpread {

    /** The table: its rows, and the totals over every method, not just the rows shown. */
    record Table(List<RpcRow> rows, long calls, long errors) {

        static final Table EMPTY = new Table(List.of(), 0L, 0L);

        Table {
            rows = List.copyOf(rows);
        }
    }

    /** How long a pooled table is reused. */
    static final Duration REFRESH = Duration.ofSeconds(1);
    /** Rows shown: the busiest methods. */
    static final int TOP_METHODS = 12;

    private static final double NANOS_PER_MS = 1_000_000.0;
    private static final int P50 = 50;
    private static final int P95 = 95;
    private static final int P99 = 99;

    private Scope scope;
    private Instant computedAt;
    private Table table = Table.EMPTY;

    /**
     * The table for {@code scope} pooled from {@code sources}, reused when the
     * last one is for the same scope and younger than {@link #REFRESH}.
     */
    Table table(Scope scope, List<RpcMetrics> sources, Instant now) {
        boolean isFresh = scope.equals(this.scope) && computedAt != null
                && Duration.between(computedAt, now).compareTo(REFRESH) < 0;
        if (!isFresh) {
            table = pool(sources);
            this.scope = scope;
            computedAt = now;
        }
        return table;
    }

    /** Forgets the cached table, so the next read pools again. */
    void invalidate() {
        computedAt = null;
    }

    static Table pool(List<RpcMetrics> sources) {
        Map<String, RpcMetrics.MethodStats> pooled = RpcMetrics.pooled(sources);
        long calls = 0L;
        long errors = 0L;
        for (RpcMetrics.MethodStats stats : pooled.values()) {
            calls += stats.callCount();
            errors += stats.errorCount();
        }
        List<RpcRow> rows = pooled.entrySet().stream()
                .map(e -> row(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(RpcRow::calls).reversed().thenComparing(RpcRow::method))
                .limit(TOP_METHODS)
                .toList();
        return new Table(rows, calls, errors);
    }

    private static RpcRow row(String method, RpcMetrics.MethodStats stats) {
        return new RpcRow(method, stats.callCount(), stats.errorCount(), stats.avgLatencyMs(),
                ms(stats.percentileNanos(P50)), ms(stats.percentileNanos(P95)), ms(stats.percentileNanos(P99)));
    }

    private static double ms(long nanos) {
        return nanos / NANOS_PER_MS;
    }
}
