package com.botwithus.bot.cli.diag;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;

import java.util.function.BooleanSupplier;

/**
 * Applies the two Diagnostics switches, {@link SettingKeys#COLLECT_RPC_TIMING}
 * and {@link SettingKeys#COLLECT_LOOP_TIMING}, to every client.
 *
 * <p>Off means the bookkeeping behind the Dashboard's tables stops: per-method
 * RPC counts and latency samples, and each script's loop count, total, min and
 * max. The pulse lane and a script's last loop keep updating either way, since
 * the lane is how a user sees that a script is looping at all. Figures already
 * collected stay until Reset metrics.</p>
 *
 * <p>Each client reads the switches through a gate that is a volatile read, so a
 * change applies to the very next call or loop, with no walk over the clients.</p>
 */
public final class MetricsCollection {

    private volatile boolean isCollectingRpc;
    private volatile boolean isCollectingLoops;
    private final BooleanSupplier rpcGate = () -> isCollectingRpc;
    private final BooleanSupplier loopGate = () -> isCollectingLoops;

    /** Reads the two settings now and follows every later change to them. */
    public MetricsCollection(HostSettings settings) {
        isCollectingRpc = settings.get(SettingKeys.COLLECT_RPC_TIMING);
        isCollectingLoops = settings.get(SettingKeys.COLLECT_LOOP_TIMING);
        settings.onChange(SettingKeys.COLLECT_RPC_TIMING, isOn -> isCollectingRpc = isOn);
        settings.onChange(SettingKeys.COLLECT_LOOP_TIMING, isOn -> isCollectingLoops = isOn);
    }

    /**
     * Puts every client {@code ctx} has now, and every client it opens from
     * here on, under these switches. A client opened before this call but not
     * yet reported on the host bus is covered by both paths, which is harmless.
     */
    public void bind(CliContext ctx) {
        ctx.getHostEvents().subscribe(event -> {
            if (ClientOpenings.isClientOpened(event)) {
                ctx.getConnections().forEach(this::attach);
            }
        });
        ctx.getConnections().forEach(this::attach);
    }

    /** Puts one client's RPC metrics and script runners under these switches. */
    public void attach(Connection conn) {
        conn.getRpc().getMetrics().setCollecting(rpcGate);
        conn.getRuntime().setLoopTimingGate(loopGate);
    }
}
