package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.core.config.ScriptProfileStore;
import com.botwithus.bot.core.rpc.RpcMetrics;
import com.botwithus.bot.core.runtime.ScriptProfiler;
import com.botwithus.bot.core.runtime.ScriptRunner;

import imgui.ImGui;
import imgui.flag.ImGuiTableFlags;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Settings dashboard — live stats, auto-start config, RPC metrics, and script profiling.
 *
 * Laid out as a dashboard: a row of hero stat cards on top, then grouped
 * section cards below. All sizing derives from font metrics so the layout
 * remains consistent across DPI scales.
 */
public class SettingsPanel implements GuiPanel {

    @Override
    public String title() {
        return "Settings";
    }

    @Override
    public void render(CliContext ctx) {
        float fontH = ImGui.getFontSize();

        renderStatRow(ctx);
        ImGui.dummy(0f, fontH * 0.4f);

        renderAutoStartCard(ctx);
        ImGui.dummy(0f, fontH * 0.4f);

        renderMetricsCard(ctx);
        ImGui.dummy(0f, fontH * 0.4f);

        renderProfilingCard(ctx);
        ImGui.dummy(0f, fontH * 0.4f);

        renderConfigCard();
    }

    // ─────────────────────────────────────────────────────────────────
    // Top stat row
    // ─────────────────────────────────────────────────────────────────

    private void renderStatRow(CliContext ctx) {
        int connCount = ctx.getConnections().size();
        int activeConn = ctx.hasActiveConnection() ? 1 : 0;

        long totalCalls = 0;
        long totalNanos = 0;
        long totalErrors = 0;
        int running = 0;
        for (Connection conn : ctx.getConnections()) {
            Map<String, RpcMetrics.MethodStats> snap = conn.getRpc().getMetrics().snapshot();
            for (RpcMetrics.MethodStats s : snap.values()) {
                totalCalls += s.callCount();
                totalNanos += s.totalTimeNanos();
                totalErrors += s.errorCount();
            }
            for (ScriptRunner r : conn.getRuntime().getRunners()) {
                if (r.isRunning()) running++;
            }
        }
        double avgMs = totalCalls > 0 ? (totalNanos / (double) totalCalls) / 1_000_000.0 : 0.0;

        float avail = ImGui.getContentRegionAvailX();
        float gap = ImGui.getStyle().getItemSpacingX();
        float cardW = (avail - gap * 3f) / 4f;

        GuiHelpers.statCard(cardW, Icons.PLUG, "Connections",
                connCount + (activeConn > 0 ? " · 1 active" : ""),
                ImGuiTheme.BLUE_R, ImGuiTheme.BLUE_G, ImGuiTheme.BLUE_B);
        ImGui.sameLine(0, gap);

        GuiHelpers.statCard(cardW, Icons.CODE, "Running scripts",
                String.valueOf(running),
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B);
        ImGui.sameLine(0, gap);

        GuiHelpers.statCard(cardW, Icons.BOLT, "RPC calls",
                formatCount(totalCalls),
                ImGuiTheme.CYAN_R, ImGuiTheme.CYAN_G, ImGuiTheme.CYAN_B);
        ImGui.sameLine(0, gap);

        float errR = totalErrors > 0 ? ImGuiTheme.RED_R : ImGuiTheme.YELLOW_R;
        float errG = totalErrors > 0 ? ImGuiTheme.RED_G : ImGuiTheme.YELLOW_G;
        float errB = totalErrors > 0 ? ImGuiTheme.RED_B : ImGuiTheme.YELLOW_B;
        String latVal = totalCalls > 0 ? String.format("%.2f ms", avgMs) : "—";
        GuiHelpers.statCard(cardW, Icons.CLOCK, "Avg latency",
                latVal,
                errR, errG, errB);
    }

    private String formatCount(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fk", n / 1_000.0);
        return String.valueOf(n);
    }

    // ─────────────────────────────────────────────────────────────────
    // Auto-start card
    // ─────────────────────────────────────────────────────────────────

    private void renderAutoStartCard(CliContext ctx) {
        float fontH = ImGui.getFontSize();
        if (!beginSectionCard("##sec_autostart", Icons.BOLT, "Auto-Start",
                "Reconnect and launch scripts on startup")) {
            endSectionCard();
            return;
        }

        ScriptProfileStore store = ctx.getProfileStore();
        if (store == null) {
            GuiHelpers.textMuted("Profile store not available.");
            endSectionCard();
            return;
        }

        // Auto-connect toggle row — label on the left, toggle right-aligned.
        float rowStartX = ImGui.getCursorPosX();
        float avail = ImGui.getContentRegionAvailX();
        float toggleW = fontH * 1.05f * 1.9f;

        ImGui.beginGroup();
        ImGui.textColored(
                ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.95f,
                "Auto-connect on startup");
        GuiHelpers.textMuted("Opens saved pipe connections automatically when the CLI launches.");
        ImGui.endGroup();

        // Jump the toggle to the top-right of the row
        ImGui.sameLine(rowStartX + avail - toggleW);
        boolean current = store.isAutoConnect();
        if (GuiHelpers.toggleSwitch("autoconnect", current)) {
            store.setAutoConnect(!current);
            store.saveSettings();
        }

        ImGui.dummy(0f, fontH * 0.3f);
        GuiHelpers.subtleSeparator();
        ImGui.dummy(0f, fontH * 0.3f);

        // Per-account profiles
        Map<String, List<String>> profiles = store.listAccountProfiles();
        if (profiles.isEmpty()) {
            renderEmptyState(Icons.USERS, "No saved profiles",
                    "Use the Accounts panel to save an account + script selection for auto-start.");
        } else {
            int flags = ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp
                    | ImGuiTableFlags.BordersInnerH | ImGuiTableFlags.NoHostExtendX;
            if (ImGui.beginTable("autoStartTable", 4, flags)) {
                ImGui.tableSetupColumn("Account", 0, 1.0f);
                ImGui.tableSetupColumn("Scripts", 0, 2.2f);
                ImGui.tableSetupColumn("Auto-Start", 0, 0.5f);
                ImGui.tableSetupColumn("", 0, 0.5f);
                ImGui.tableHeadersRow();

                int idx = 0;
                for (var entry : profiles.entrySet()) {
                    String account = entry.getKey();
                    List<String> scripts = entry.getValue();

                    ImGui.tableNextRow();

                    ImGui.tableSetColumnIndex(0);
                    ImGui.textColored(
                            ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.95f,
                            account);

                    ImGui.tableSetColumnIndex(1);
                    if (scripts.isEmpty()) {
                        GuiHelpers.textMuted("none");
                    } else {
                        GuiHelpers.textSecondary(String.join(" · ", scripts));
                    }

                    ImGui.tableSetColumnIndex(2);
                    ImGui.pushID("as_toggle_" + idx);
                    boolean enabled = store.isAutoStart(account);
                    if (GuiHelpers.toggleSwitch("##t", enabled)) {
                        store.setAutoStart(account, !enabled);
                    }
                    ImGui.popID();

                    ImGui.tableSetColumnIndex(3);
                    ImGui.pushID("as_clear_" + idx);
                    if (GuiHelpers.smallButtonDanger("Clear")) {
                        store.clearAccountProfile(account);
                    }
                    ImGui.popID();

                    idx++;
                }

                ImGui.endTable();
            }
        }

        endSectionCard();
    }

    // ─────────────────────────────────────────────────────────────────
    // RPC metrics card
    // ─────────────────────────────────────────────────────────────────

    private void renderMetricsCard(CliContext ctx) {
        if (!beginSectionCard("##sec_metrics", Icons.CHART_BAR, "RPC Metrics",
                "Top 20 methods by call volume on the active connection")) {
            endSectionCard();
            return;
        }

        Connection conn = ctx.getActiveConnection();
        if (conn == null) {
            renderEmptyState(Icons.PLUG, "No active connection",
                    "Connect to a game instance to view RPC metrics.");
            endSectionCard();
            return;
        }

        RpcMetrics metrics = conn.getRpc().getMetrics();
        Map<String, RpcMetrics.MethodStats> snapshot = metrics.snapshot();

        // Right-aligned reset button
        float resetW = ImGui.calcTextSize(Icons.ROTATE + "  Reset").x
                + ImGui.getStyle().getFramePaddingX() * 2f + ImGui.getFontSize();
        ImGui.setCursorPosX(ImGui.getCursorPosX() + ImGui.getContentRegionAvailX() - resetW);
        if (GuiHelpers.buttonSecondary(Icons.ROTATE + "  Reset")) {
            metrics.reset();
        }
        ImGui.dummy(0f, ImGui.getFontSize() * 0.2f);

        if (snapshot.isEmpty()) {
            renderEmptyState(Icons.CHART_BAR, "No metrics yet",
                    "RPC call metrics will appear here once the active connection processes requests.");
            endSectionCard();
            return;
        }

        var entries = snapshot.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, RpcMetrics.MethodStats>>comparingLong(
                        e -> e.getValue().callCount()).reversed())
                .limit(20)
                .toList();

        int flags = ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp
                | ImGuiTableFlags.BordersInnerH | ImGuiTableFlags.ScrollY
                | ImGuiTableFlags.NoHostExtendX;
        float tableH = ImGui.getFontSize() * 14f;
        if (ImGui.beginTable("metricsTable", 4, flags, 0f, tableH)) {
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
                GuiHelpers.textSecondary(String.valueOf(stats.callCount()));

                ImGui.tableSetColumnIndex(2);
                double avg = stats.avgLatencyMs();
                if (avg > 50.0) {
                    ImGui.textColored(ImGuiTheme.YELLOW_R, ImGuiTheme.YELLOW_G, ImGuiTheme.YELLOW_B, 1f,
                            String.format("%.2f", avg));
                } else {
                    ImGui.text(String.format("%.2f", avg));
                }

                ImGui.tableSetColumnIndex(3);
                if (stats.errorCount() > 0) {
                    ImGui.textColored(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 1f,
                            String.valueOf(stats.errorCount()));
                } else {
                    GuiHelpers.textMuted("0");
                }
            }

            ImGui.endTable();
        }

        endSectionCard();
    }

    // ─────────────────────────────────────────────────────────────────
    // Script profiling card
    // ─────────────────────────────────────────────────────────────────

    private void renderProfilingCard(CliContext ctx) {
        if (!beginSectionCard("##sec_profiling", Icons.CODE, "Script Profiling",
                "Loop timings for scripts on the active connection")) {
            endSectionCard();
            return;
        }

        Connection conn = ctx.getActiveConnection();
        if (conn == null) {
            renderEmptyState(Icons.PLUG, "No active connection",
                    "Connect to view script performance metrics.");
            endSectionCard();
            return;
        }

        List<ScriptRunner> runners = conn.getRuntime().getRunners();
        if (runners.isEmpty()) {
            renderEmptyState(Icons.CODE, "No scripts loaded",
                    "Mount scripts from the Scripts panel to see profiling data.");
            endSectionCard();
            return;
        }

        float resetW = ImGui.calcTextSize(Icons.ROTATE + "  Reset").x
                + ImGui.getStyle().getFramePaddingX() * 2f + ImGui.getFontSize();
        ImGui.setCursorPosX(ImGui.getCursorPosX() + ImGui.getContentRegionAvailX() - resetW);
        if (GuiHelpers.buttonSecondary(Icons.ROTATE + "  Reset")) {
            for (ScriptRunner runner : runners) {
                runner.getProfiler().reset();
            }
        }
        ImGui.dummy(0f, ImGui.getFontSize() * 0.2f);

        int flags = ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp
                | ImGuiTableFlags.BordersInnerH | ImGuiTableFlags.NoHostExtendX;
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
                if (runner.isRunning()) {
                    GuiHelpers.statusDot(ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B);
                    ImGui.sameLine(0, ImGui.getStyle().getItemInnerSpacingX());
                }
                ImGui.text(runner.getScriptName());

                ImGui.tableSetColumnIndex(1);
                GuiHelpers.textSecondary(String.valueOf(p.getLoopCount()));

                ImGui.tableSetColumnIndex(2);
                ImGui.text(String.format("%.2f", p.avgLoopMs()));

                ImGui.tableSetColumnIndex(3);
                GuiHelpers.textSecondary(String.format("%.2f", p.getMinLoopNanos() / 1_000_000.0));

                ImGui.tableSetColumnIndex(4);
                double maxMs = p.getMaxLoopNanos() / 1_000_000.0;
                if (maxMs > 100.0) {
                    ImGui.textColored(ImGuiTheme.YELLOW_R, ImGuiTheme.YELLOW_G, ImGuiTheme.YELLOW_B, 1f,
                            String.format("%.2f", maxMs));
                } else {
                    ImGui.text(String.format("%.2f", maxMs));
                }

                ImGui.tableSetColumnIndex(5);
                ImGui.text(String.format("%.2f", p.getLastLoopNanos() / 1_000_000.0));
            }

            ImGui.endTable();
        }

        endSectionCard();
    }

    // ─────────────────────────────────────────────────────────────────
    // CLI config card
    // ─────────────────────────────────────────────────────────────────

    private void renderConfigCard() {
        if (!beginSectionCard("##sec_config", Icons.GEAR, "CLI Configuration", null)) {
            endSectionCard();
            return;
        }
        GuiHelpers.textSecondary("Configuration is managed via the Console panel:");
        ImGui.dummy(0f, ImGui.getFontSize() * 0.25f);
        GuiHelpers.kbdHint("config show");
        ImGui.sameLine(0, ImGui.getStyle().getItemSpacingX());
        GuiHelpers.textMuted("list all keys");
        ImGui.dummy(0f, ImGui.getFontSize() * 0.2f);
        GuiHelpers.kbdHint("config set <key> <value>");
        ImGui.sameLine(0, ImGui.getStyle().getItemSpacingX());
        GuiHelpers.textMuted("update a key");
        endSectionCard();
    }

    // ─────────────────────────────────────────────────────────────────
    // Section card primitives
    // ─────────────────────────────────────────────────────────────────

    /**
     * Start a flat section with an accent icon, title, optional subtitle, and a
     * subtle rule beneath. No child window — sizes are driven by the content
     * that follows so the section never overflows or clips.
     */
    private boolean beginSectionCard(String id, String icon, String title, String subtitle) {
        float fontH = ImGui.getFontSize();
        var draw = ImGui.getWindowDrawList();

        float cx = ImGui.getCursorScreenPosX();
        float cy = ImGui.getCursorScreenPosY();

        int accent = ImGuiTheme.imCol32(
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.9f);
        int titleCol = ImGuiTheme.imCol32(
                ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.95f);
        int subCol = ImGuiTheme.imCol32(
                ImGuiTheme.TEXT_SEC_R, ImGuiTheme.TEXT_SEC_G, ImGuiTheme.TEXT_SEC_B, 0.7f);

        float iconW = fontH * 1.6f;
        draw.addText(cx, cy, accent, icon);
        draw.addText(cx + iconW, cy, titleCol, title);
        if (subtitle != null) {
            draw.addText(cx + iconW, cy + fontH * 1.2f, subCol, subtitle);
        }

        ImGui.dummy(0f, subtitle != null ? fontH * 2.3f : fontH * 1.3f);
        GuiHelpers.subtleSeparator();
        ImGui.dummy(0f, fontH * 0.25f);
        return true;
    }

    private void endSectionCard() {
        // Plain group-based section — nothing to unwind.
    }

    private void renderEmptyState(String icon, String title, String subtitle) {
        float fontH = ImGui.getFontSize();
        float avail = ImGui.getContentRegionAvailX();

        ImGui.dummy(0f, fontH * 0.6f);
        // Centered icon
        float iconW = ImGui.calcTextSize(icon).x;
        ImGui.setCursorPosX(ImGui.getCursorPosX() + (avail - iconW) * 0.5f);
        ImGui.textColored(
                ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 0.7f, icon);
        ImGui.dummy(0f, fontH * 0.25f);

        float titleW = ImGui.calcTextSize(title).x;
        ImGui.setCursorPosX(ImGui.getCursorPosX() + (avail - titleW) * 0.5f);
        ImGui.textColored(
                ImGuiTheme.TEXT_SEC_R, ImGuiTheme.TEXT_SEC_G, ImGuiTheme.TEXT_SEC_B, 0.85f, title);

        float subW = ImGui.calcTextSize(subtitle).x;
        if (subW < avail) {
            ImGui.setCursorPosX(ImGui.getCursorPosX() + (avail - subW) * 0.5f);
        }
        ImGui.textColored(
                ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 0.65f, subtitle);
        ImGui.dummy(0f, fontH * 0.6f);
    }
}
