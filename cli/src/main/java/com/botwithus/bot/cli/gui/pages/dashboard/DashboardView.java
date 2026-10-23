package com.botwithus.bot.cli.gui.pages.dashboard;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Everything the Dashboard's page body shows for one frame, for one scope.
 * Immutable: the model builds a fresh one each time it is asked.
 *
 * @param since     when the metrics were last reset, or when the host started
 * @param isReset   whether {@code since} is a reset rather than the start
 * @param runners   worst first, then by client and script
 * @param rpc       busiest method first
 * @param attention errors first, then newest first
 * @param scopes    what the scope picker offers, "All clients" first
 * @param isCollectingRpc   the Diagnostics switch for RPC timing
 * @param isCollectingLoops the Diagnostics switch for loop timing
 */
public record DashboardView(Instant since, boolean isReset, Kpis kpis, List<RunnerRow> runners,
                            List<RpcRow> rpc, List<AttentionItem> attention, DevLoopView devLoop,
                            List<ScopeOption> scopes, boolean isCollectingRpc, boolean isCollectingLoops) {

    public DashboardView {
        Objects.requireNonNull(since, "since");
        Objects.requireNonNull(kpis, "kpis");
        Objects.requireNonNull(devLoop, "devLoop");
        runners = List.copyOf(runners);
        rpc = List.copyOf(rpc);
        attention = List.copyOf(attention);
        scopes = List.copyOf(scopes);
    }
}
