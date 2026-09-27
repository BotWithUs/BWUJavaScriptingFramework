package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.core.rpc.RpcMetrics;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Each pipe's average RPC round trip, for the card's RPC figure. Taking a
 * metrics snapshot sorts every method's latency window, which is too much to do
 * for every card on every frame, so each pipe's average is worked out at most
 * once per {@link #REFRESH}. Render thread only.
 */
final class RpcAverages {

    /** How long a pipe's average is shown before it is worked out again. */
    static final Duration REFRESH = Duration.ofSeconds(1);
    private static final double NANOS_PER_MS = 1_000_000.0;

    private record Cached(OptionalDouble averageMs, Instant at) { }

    private final Map<String, Cached> byPipe = new HashMap<>();

    /**
     * The average round trip on {@code pipe} across every method, or empty
     * before its first call.
     *
     * @param snapshot takes a snapshot of the pipe's metrics; called only when due
     */
    OptionalDouble of(String pipe, Supplier<Map<String, RpcMetrics.MethodStats>> snapshot, Instant now) {
        Cached cached = byPipe.get(pipe);
        if (cached == null || !now.isBefore(cached.at().plus(REFRESH))) {
            cached = new Cached(averageOf(snapshot.get()), now);
            byPipe.put(pipe, cached);
        }
        return cached.averageMs();
    }

    /** Drops every pipe not in {@code pipes}. */
    void retain(Collection<String> pipes) {
        byPipe.keySet().retainAll(pipes);
    }

    /** Total time over total calls, across every method; empty with no calls. */
    static OptionalDouble averageOf(Map<String, RpcMetrics.MethodStats> stats) {
        long calls = 0;
        long nanos = 0;
        for (RpcMetrics.MethodStats method : stats.values()) {
            calls += method.callCount();
            nanos += method.totalTimeNanos();
        }
        return calls > 0 ? OptionalDouble.of(nanos / NANOS_PER_MS / calls) : OptionalDouble.empty();
    }
}
