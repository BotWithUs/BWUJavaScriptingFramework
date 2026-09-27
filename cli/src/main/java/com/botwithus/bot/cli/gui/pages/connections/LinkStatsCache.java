package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcMetrics;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Each live connection's RPC totals, re-read at most once a second. Reading them
 * walks every method's latency window, which is too much to do for every
 * connection every frame. Uptime is not cached: it is one subtraction.
 *
 * <p>Render thread only.</p>
 */
final class LinkStatsCache {

    /** How long a pipe's RPC totals are reused before they are read again. */
    static final Duration REFRESH = Duration.ofSeconds(1);

    private static final double NANOS_PER_MS = 1_000_000.0;

    private record Totals(long calls, long errors, double avgMs, Instant readAt) { }

    private final Map<String, Totals> byPipe = new HashMap<>();

    /** Stats for every connection that has an RPC client, keyed by pipe. */
    Map<String, LinkStats> read(List<Connection> connections, Instant now) {
        Set<String> live = connections.stream().map(Connection::getName).collect(Collectors.toSet());
        byPipe.keySet().retainAll(live);
        Map<String, LinkStats> stats = new HashMap<>();
        for (Connection conn : connections) {
            totalsOf(conn, now).ifPresent(totals -> stats.put(conn.getName(),
                    new LinkStats(uptimeOf(conn, now), totals.calls(), totals.errors(), totals.avgMs())));
        }
        return stats;
    }

    private Optional<Totals> totalsOf(Connection conn, Instant now) {
        Totals cached = byPipe.get(conn.getName());
        if (cached != null && now.isBefore(cached.readAt().plus(REFRESH))) {
            return Optional.of(cached);
        }
        RpcClient rpc = conn.getRpc();
        if (rpc == null) {
            return Optional.empty();
        }
        Totals fresh = sum(rpc.getMetrics(), now);
        byPipe.put(conn.getName(), fresh);
        return Optional.of(fresh);
    }

    private static Totals sum(RpcMetrics metrics, Instant now) {
        long calls = 0L;
        long errors = 0L;
        long nanos = 0L;
        for (RpcMetrics.MethodStats method : metrics.snapshot().values()) {
            calls += method.callCount();
            errors += method.errorCount();
            nanos += method.totalTimeNanos();
        }
        double avg = calls > 0 ? nanos / NANOS_PER_MS / calls : 0.0;
        return new Totals(calls, errors, avg, now);
    }

    private static Duration uptimeOf(Connection conn, Instant now) {
        Instant since = conn.getConnectedAt();
        if (since == null || since.isAfter(now)) {
            return Duration.ZERO;
        }
        return Duration.between(since, now);
    }
}
