package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.core.rpc.RpcMetrics;
import com.botwithus.bot.core.runtime.ScriptProfiler;
import com.botwithus.bot.core.runtime.ScriptRunner;

import imgui.ImGui;
import imgui.flag.ImGuiTableFlags;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Diagnostics panel — surfaces the RPC and script-loop metrics that the
 * framework already collects internally ({@link RpcMetrics}, {@link ScriptProfiler})
 * but never previously displayed.
 *
 * <p>Two tables: per-method RPC stats (call count, error count, avg / p50 / p95 / p99
 * latency) and per-script loop profile (loops, avg / min / max / last loop ms),
 * aggregated across every active connection.</p>
 */
public class DiagnosticsPanel implements GuiPanel {

    public DiagnosticsPanel() {}

    private static final int TOP_METHOD_LIMIT = 25;
    private static final double LATENCY_WARN_MS = 50.0;
    private static final double LOOP_WARN_MS = 100.0;

    @Override
    public String title() {
        return "Diagnostics";
    }

    @Override
    public void render(CliContext ctx) {
        float fontH = ImGui.getFontSize();

        renderRpcMetricsTable(ctx);
        ImGui.dummy(0f, fontH * 0.4f);
        renderScriptProfilerTable(ctx);
    }

    private static void renderRpcMetricsTable(CliContext ctx) {
        renderSectionTitle(Icons.SIGNAL, "RPC Latency");

        Connection conn = ctx.getActiveConnection();
        if (conn == null) {
            GuiHelpers.textMuted("No active connection — RPC metrics are empty.");
            return;
        }

        RpcMetrics metrics = conn.getRpc().getMetrics();
        Map<String, RpcMetrics.MethodStats> snapshot = metrics.snapshot();

        renderResetRow(Icons.ROTATE + "  Reset RPC", metrics::reset);

        if (snapshot.isEmpty()) {
            GuiHelpers.textMuted("Awaiting RPC traffic — call statistics will appear here once requests run.");
            return;
        }

        List<Map.Entry<String, RpcMetrics.MethodStats>> entries = snapshot.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, RpcMetrics.MethodStats>>comparingLong(
                        e -> e.getValue().callCount()).reversed())
                .limit(TOP_METHOD_LIMIT)
                .toList();

        int flags = ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp
                | ImGuiTableFlags.BordersInnerH | ImGuiTableFlags.ScrollY
                | ImGuiTableFlags.NoHostExtendX;
        float tableH = ImGui.getFontSize() * 16f;
        if (ImGui.beginTable("diagRpcTable", 7, flags, 0f, tableH)) {
            ImGui.tableSetupColumn("Method", 0, 2.2f);
            ImGui.tableSetupColumn("Calls", 0, 0.5f);
            ImGui.tableSetupColumn("Errors", 0, 0.5f);
            ImGui.tableSetupColumn("Avg (ms)", 0, 0.6f);
            ImGui.tableSetupColumn("P50 (ms)", 0, 0.6f);
            ImGui.tableSetupColumn("P95 (ms)", 0, 0.6f);
            ImGui.tableSetupColumn("P99 (ms)", 0, 0.6f);
            ImGui.tableHeadersRow();

            for (Map.Entry<String, RpcMetrics.MethodStats> entry : entries) {
                renderRpcRow(entry.getKey(), entry.getValue());
            }
            ImGui.endTable();
        }
    }

    private static void renderRpcRow(String method, RpcMetrics.MethodStats stats) {
        ImGui.tableNextRow();

        ImGui.tableSetColumnIndex(0);
        ImGui.text(method);

        ImGui.tableSetColumnIndex(1);
        GuiHelpers.textSecondary(Long.toString(stats.callCount()));

        ImGui.tableSetColumnIndex(2);
        if (stats.errorCount() > 0) {
            ImGui.textColored(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 1f,
                    Long.toString(stats.errorCount()));
        } else {
            GuiHelpers.textMuted("0");
        }

        ImGui.tableSetColumnIndex(3);
        renderLatencyCell(stats.avgLatencyMs(), LATENCY_WARN_MS);

        ImGui.tableSetColumnIndex(4);
        renderLatencyCell(stats.percentileMs(50), LATENCY_WARN_MS);

        ImGui.tableSetColumnIndex(5);
        renderLatencyCell(stats.percentileMs(95), LATENCY_WARN_MS);

        ImGui.tableSetColumnIndex(6);
        renderLatencyCell(stats.percentileMs(99), LATENCY_WARN_MS);
    }

    private static void renderScriptProfilerTable(CliContext ctx) {
        renderSectionTitle(Icons.CODE, "Script Loops");

        List<ScriptRunner> runners = collectAllRunners(ctx);
        if (runners.isEmpty()) {
            GuiHelpers.textMuted("No scripts registered on any active connection.");
            return;
        }

        renderResetRow(Icons.ROTATE + "  Reset Profilers", () -> {
            for (ScriptRunner runner : runners) {
                runner.getProfiler().reset();
            }
        });

        int flags = ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp
                | ImGuiTableFlags.BordersInnerH | ImGuiTableFlags.NoHostExtendX;
        if (ImGui.beginTable("diagProfilerTable", 7, flags)) {
            ImGui.tableSetupColumn("Connection", 0, 1.1f);
            ImGui.tableSetupColumn("Script", 0, 1.7f);
            ImGui.tableSetupColumn("Loops", 0, 0.5f);
            ImGui.tableSetupColumn("Avg (ms)", 0, 0.6f);
            ImGui.tableSetupColumn("Min (ms)", 0, 0.6f);
            ImGui.tableSetupColumn("Max (ms)", 0, 0.6f);
            ImGui.tableSetupColumn("Last (ms)", 0, 0.6f);
            ImGui.tableHeadersRow();

            for (ScriptRunner runner : runners) {
                renderProfilerRow(runner);
            }
            ImGui.endTable();
        }
    }

    private static void renderProfilerRow(ScriptRunner runner) {
        ScriptProfiler p = runner.getProfiler();
        ImGui.tableNextRow();

        ImGui.tableSetColumnIndex(0);
        String connName = runner.getConnectionName();
        GuiHelpers.textMuted(connName != null ? connName : "—");

        ImGui.tableSetColumnIndex(1);
        if (runner.isRunning()) {
            GuiHelpers.statusDot(ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B);
            ImGui.sameLine(0, ImGui.getStyle().getItemInnerSpacingX());
        }
        ImGui.text(runner.getScriptName());

        ImGui.tableSetColumnIndex(2);
        GuiHelpers.textSecondary(Long.toString(p.getLoopCount()));

        ImGui.tableSetColumnIndex(3);
        ImGui.text(String.format("%.2f", p.avgLoopMs()));

        ImGui.tableSetColumnIndex(4);
        GuiHelpers.textSecondary(String.format("%.2f", p.getMinLoopNanos() / 1_000_000.0));

        ImGui.tableSetColumnIndex(5);
        double maxMs = p.getMaxLoopNanos() / 1_000_000.0;
        renderLatencyCell(maxMs, LOOP_WARN_MS);

        ImGui.tableSetColumnIndex(6);
        ImGui.text(String.format("%.2f", p.getLastLoopNanos() / 1_000_000.0));
    }

    private static void renderLatencyCell(double valueMs, double warnThresholdMs) {
        if (valueMs >= warnThresholdMs) {
            ImGui.textColored(ImGuiTheme.YELLOW_R, ImGuiTheme.YELLOW_G, ImGuiTheme.YELLOW_B, 1f,
                    String.format("%.2f", valueMs));
        } else {
            ImGui.text(String.format("%.2f", valueMs));
        }
    }

    private static void renderSectionTitle(String icon, String title) {
        ImGui.textColored(ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.9f, icon);
        ImGui.sameLine(0, 8);
        ImGui.textColored(ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.95f, title);
        ImGui.dummy(0f, ImGui.getFontSize() * 0.2f);
        GuiHelpers.subtleSeparator();
        ImGui.dummy(0f, ImGui.getFontSize() * 0.2f);
    }

    private static void renderResetRow(String label, Runnable onClick) {
        float resetW = ImGui.calcTextSize(label).x
                + ImGui.getStyle().getFramePaddingX() * 2f + ImGui.getFontSize();
        ImGui.setCursorPosX(ImGui.getCursorPosX() + ImGui.getContentRegionAvailX() - resetW);
        if (GuiHelpers.buttonSecondary(label)) {
            onClick.run();
        }
        ImGui.dummy(0f, ImGui.getFontSize() * 0.2f);
    }

    private static List<ScriptRunner> collectAllRunners(CliContext ctx) {
        List<ScriptRunner> result = new ArrayList<>();
        for (Connection conn : ctx.getConnections()) {
            if (conn.isAlive()) {
                result.addAll(conn.getRuntime().getRunners());
            }
        }
        return result;
    }
}
