package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.core.config.ScriptProfileStore;
import com.botwithus.bot.core.rpc.RpcMetrics;
import com.botwithus.bot.core.runtime.ScriptProfiler;
import com.botwithus.bot.core.runtime.ScriptRunner;

import imgui.ImGui;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImBoolean;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Settings panel — auto-start config, RPC metrics, script profiling, CLI config.
 */
public class SettingsPanel implements GuiPanel {

    @Override
    public String title() {
        return "Settings";
    }

    @Override
    public void render(CliContext ctx) {
        // Auto-start section
        if (ImGui.collapsingHeader("Auto-Start Configuration", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.spacing();
            renderAutoStartSection(ctx);
        }

        ImGui.spacing();
        ImGui.spacing();

        // Metrics section
        if (ImGui.collapsingHeader("RPC Metrics")) {
            ImGui.spacing();
            renderMetricsSection(ctx);
        }

        ImGui.spacing();
        ImGui.spacing();

        // Profiling section
        if (ImGui.collapsingHeader("Script Profiling")) {
            ImGui.spacing();
            renderProfilingSection(ctx);
        }

        ImGui.spacing();
        ImGui.spacing();

        // CLI Config section
        if (ImGui.collapsingHeader("CLI Configuration")) {
            ImGui.spacing();
            renderConfigSection(ctx);
        }
    }

    private void renderAutoStartSection(CliContext ctx) {
        ScriptProfileStore store = ctx.getProfileStore();
        if (store == null) {
            ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f,
                    "Profile store not available.");
            return;
        }

        // Global auto-connect toggle
        ImBoolean autoConnect = new ImBoolean(store.isAutoConnect());
        if (ImGui.checkbox("Auto-Connect on startup", autoConnect)) {
            store.setAutoConnect(autoConnect.get());
            store.saveSettings();
        }

        ImGui.spacing();

        // Per-account profiles
        Map<String, List<String>> profiles = store.listAccountProfiles();
        if (profiles.isEmpty()) {
            ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f,
                    "No account profiles saved yet.");
        } else {
            int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
            if (ImGui.beginTable("autoStartTable", 4, flags)) {
                ImGui.tableSetupColumn("Account", 0, 1.0f);
                ImGui.tableSetupColumn("Scripts", 0, 2.0f);
                ImGui.tableSetupColumn("Auto-Start", 0, 0.6f);
                ImGui.tableSetupColumn("Actions", 0, 0.6f);
                ImGui.tableHeadersRow();

                int idx = 0;
                for (var entry : profiles.entrySet()) {
                    String account = entry.getKey();
                    List<String> scripts = entry.getValue();

                    ImGui.tableNextRow();

                    ImGui.tableSetColumnIndex(0);
                    ImGui.text(account);

                    ImGui.tableSetColumnIndex(1);
                    ImGui.text(scripts.isEmpty() ? "(none)" : String.join(", ", scripts));

                    ImGui.tableSetColumnIndex(2);
                    ImGui.pushID("as_toggle_" + idx);
                    ImBoolean enabled = new ImBoolean(store.isAutoStart(account));
                    if (ImGui.checkbox("##enabled", enabled)) {
                        store.setAutoStart(account, enabled.get());
                    }
                    ImGui.popID();

                    ImGui.tableSetColumnIndex(3);
                    ImGui.pushID("as_clear_" + idx);
                    if (ImGui.smallButton("Clear")) {
                        store.clearAccountProfile(account);
                    }
                    ImGui.popID();

                    idx++;
                }

                ImGui.endTable();
            }
        }
    }

    private void renderMetricsSection(CliContext ctx) {
        Connection conn = ctx.getActiveConnection();
        if (conn == null) {
            ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f,
                    "No active connection.");
            return;
        }

        RpcMetrics metrics = conn.getRpc().getMetrics();
        Map<String, RpcMetrics.MethodStats> snapshot = metrics.snapshot();

        if (GuiHelpers.buttonSecondary(Icons.ROTATE + "  Reset Metrics")) {
            metrics.reset();
        }

        ImGui.spacing();

        if (snapshot.isEmpty()) {
            ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f,
                    "No metrics recorded yet.");
            return;
        }

        var entries = snapshot.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, RpcMetrics.MethodStats>>comparingLong(e -> e.getValue().callCount()).reversed())
                .limit(20)
                .toList();

        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp
                | ImGuiTableFlags.ScrollY;
        if (ImGui.beginTable("metricsTable", 4, flags, 0, 200)) {
            ImGui.tableSetupColumn("Method", 0, 2.0f);
            ImGui.tableSetupColumn("Calls", 0, 0.5f);
            ImGui.tableSetupColumn("Avg (ms)", 0, 0.6f);
            ImGui.tableSetupColumn("Errors", 0, 0.5f);
            ImGui.tableHeadersRow();

            for (var entry : entries) {
                RpcMetrics.MethodStats stats = entry.getValue();
                ImGui.tableNextRow();

                ImGui.tableSetColumnIndex(0);
                ImGui.text(entry.getKey());

                ImGui.tableSetColumnIndex(1);
                ImGui.text(String.valueOf(stats.callCount()));

                ImGui.tableSetColumnIndex(2);
                ImGui.text(String.format("%.2f", stats.avgLatencyMs()));

                ImGui.tableSetColumnIndex(3);
                if (stats.errorCount() > 0) {
                    ImGui.textColored(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 1f,
                            String.valueOf(stats.errorCount()));
                } else {
                    ImGui.text("0");
                }
            }

            ImGui.endTable();
        }
    }

    private void renderProfilingSection(CliContext ctx) {
        Connection conn = ctx.getActiveConnection();
        if (conn == null) {
            ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f,
                    "No active connection.");
            return;
        }

        List<ScriptRunner> runners = conn.getRuntime().getRunners();
        if (runners.isEmpty()) {
            ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f,
                    "No scripts loaded.");
            return;
        }

        if (GuiHelpers.buttonSecondary(Icons.ROTATE + "  Reset Profiling")) {
            for (ScriptRunner runner : runners) {
                runner.getProfiler().reset();
            }
        }

        ImGui.spacing();

        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
        if (ImGui.beginTable("profilingTable", 6, flags)) {
            ImGui.tableSetupColumn("Script", 0, 1.5f);
            ImGui.tableSetupColumn("Loops", 0, 0.5f);
            ImGui.tableSetupColumn("Avg (ms)", 0, 0.6f);
            ImGui.tableSetupColumn("Min (ms)", 0, 0.6f);
            ImGui.tableSetupColumn("Max (ms)", 0, 0.6f);
            ImGui.tableSetupColumn("Last (ms)", 0, 0.6f);
            ImGui.tableHeadersRow();

            for (ScriptRunner runner : runners) {
                ScriptProfiler p = runner.getProfiler();
                ImGui.tableNextRow();

                ImGui.tableSetColumnIndex(0);
                ImGui.text(runner.getScriptName());

                ImGui.tableSetColumnIndex(1);
                ImGui.text(String.valueOf(p.getLoopCount()));

                ImGui.tableSetColumnIndex(2);
                ImGui.text(String.format("%.2f", p.avgLoopMs()));

                ImGui.tableSetColumnIndex(3);
                ImGui.text(String.format("%.2f", p.getMinLoopNanos() / 1_000_000.0));

                ImGui.tableSetColumnIndex(4);
                ImGui.text(String.format("%.2f", p.getMaxLoopNanos() / 1_000_000.0));

                ImGui.tableSetColumnIndex(5);
                ImGui.text(String.format("%.2f", p.getLastLoopNanos() / 1_000_000.0));
            }

            ImGui.endTable();
        }
    }

    private void renderConfigSection(CliContext ctx) {
        GuiHelpers.textMuted("Use the Console tab with 'config show' or 'config set <key> <value>' commands.");
    }
}
