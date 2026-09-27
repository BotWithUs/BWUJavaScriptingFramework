package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.core.rpc.RpcMetrics;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A card's RPC figure: the average round trip across every method, worked out at most once a second. */
class RpcAveragesTest {

    private static final long MS = 1_000_000L;
    private static final Instant T0 = Instant.parse("2026-09-26T12:00:00Z");
    private static final String PIPE = "BotWithUs_1";

    @Test
    void theAverage_isTotalTimeOverTotalCalls_acrossMethods() {
        RpcMetrics metrics = new RpcMetrics();
        metrics.recordCall("get_account_info", 2 * MS, false);
        metrics.recordCall("get_account_info", 4 * MS, false);
        metrics.recordCall("get_current_world", 6 * MS, false);

        assertEquals(OptionalDouble.of(4.0), RpcAverages.averageOf(metrics.snapshot()));
    }

    @Test
    void withNoCalls_thereIsNoAverage() {
        assertEquals(OptionalDouble.empty(), RpcAverages.averageOf(new RpcMetrics().snapshot()));
    }

    @Test
    void aPipesAverage_isReusedUntilItIsASecondOld() {
        RpcMetrics metrics = new RpcMetrics();
        metrics.recordCall("ping", 2 * MS, false);
        AtomicInteger snapshots = new AtomicInteger();
        RpcAverages averages = new RpcAverages();

        averages.of(PIPE, () -> {
            snapshots.incrementAndGet();
            return metrics.snapshot();
        }, T0);
        metrics.recordCall("ping", 8 * MS, false);
        OptionalDouble cached = averages.of(PIPE, metrics::snapshot, T0.plus(RpcAverages.REFRESH).minusMillis(1));
        OptionalDouble fresh = averages.of(PIPE, metrics::snapshot, T0.plus(RpcAverages.REFRESH));

        assertEquals(1, snapshots.get());
        assertEquals(OptionalDouble.of(2.0), cached);
        assertEquals(OptionalDouble.of(5.0), fresh);
    }
}
