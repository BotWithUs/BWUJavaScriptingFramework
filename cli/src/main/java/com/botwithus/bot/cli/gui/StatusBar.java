package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.core.loader.BwuClient;
import com.botwithus.bot.core.runtime.ScriptRunner;

import imgui.ImDrawList;
import imgui.ImGui;

/**
 * Fixed status bar rendered at the bottom of the window, always visible regardless of active tab.
 *
 * Layout (left → right):
 *   [pulsing dot] [connection name] • [conn chip] • [scripts chip] • [mounted badge?] • [watching badge?]
 *   ... [bwu error readout, right-aligned, red, when non-empty]
 *
 * Visuals are derived from font size / style spacing so the bar scales cleanly with DPI.
 */
public class StatusBar {

    /** Max chars of the native error to render inline; full text goes in the tooltip. */
    private static final int INLINE_ERR_MAX = 80;

    private final BwuClient bwu;

    public StatusBar() {
        this(null);
    }

    public StatusBar(BwuClient bwu) {
        this.bwu = bwu;
    }

    public void render(CliContext ctx) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float w = ImGui.getContentRegionAvailX();
        float fontH = ImGui.getFontSize();

        // Soft top-border: transparent at the edges, dim in the middle — gives the bar a subtle lift
        int edge = ImGuiTheme.imCol32(
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0f);
        int mid = ImGuiTheme.imCol32(
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.45f);
        float half = w * 0.5f;
        draw.addRectFilledMultiColor(x, y, x + half, y + 1f, edge, mid, mid, edge);
        draw.addRectFilledMultiColor(x + half, y, x + w, y + 1f, mid, edge, edge, mid);

        ImGui.dummy(0f, fontH * 0.25f);

        // Gather state
        boolean connected = ctx.hasActiveConnection();
        String activeName = ctx.getActiveConnectionName();
        int connCount = ctx.getConnections().size();
        boolean mounted = ctx.isMounted();
        String mountedName = ctx.getMountedConnectionName();
        boolean watcherRunning = ctx.isWatcherRunning();

        int runningScripts = 0;
        for (Connection conn : ctx.getConnections()) {
            for (ScriptRunner runner : conn.getRuntime().getRunners()) {
                if (runner.isRunning()) runningScripts++;
            }
        }

        float gap = ImGui.getStyle().getItemSpacingX();

        // Active-connection dot (pulses when live) + name
        if (connected) {
            GuiHelpers.pulsingDot(ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B);
            ImGui.sameLine(0, gap * 0.5f);
            ImGui.textColored(ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.95f,
                    activeName != null ? activeName : "connected");
        } else {
            GuiHelpers.statusDot(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B);
            ImGui.sameLine(0, gap * 0.5f);
            GuiHelpers.textMuted("disconnected");
        }

        // Connection count chip
        ImGui.sameLine(0, gap);
        GuiHelpers.inlineDotSep();
        ImGui.sameLine(0, gap * 0.5f);
        GuiHelpers.metricChip("conn", String.valueOf(connCount),
                ImGuiTheme.TEXT_SEC_R, ImGuiTheme.TEXT_SEC_G, ImGuiTheme.TEXT_SEC_B);

        // Running scripts chip (green when active, dim when none)
        ImGui.sameLine(0, gap);
        GuiHelpers.inlineDotSep();
        ImGui.sameLine(0, gap * 0.5f);
        if (runningScripts > 0) {
            GuiHelpers.metricChip(runningScripts == 1 ? "script" : "scripts",
                    String.valueOf(runningScripts),
                    ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B);
        } else {
            GuiHelpers.textMuted("idle");
        }

        // Mounted badge
        if (mounted) {
            ImGui.sameLine(0, gap);
            GuiHelpers.inlineDotSep();
            ImGui.sameLine(0, gap * 0.5f);
            GuiHelpers.statusBadge("mounted · " + mountedName,
                    ImGuiTheme.MAGENTA_R, ImGuiTheme.MAGENTA_G, ImGuiTheme.MAGENTA_B);
        }

        // Watcher badge
        if (watcherRunning) {
            ImGui.sameLine(0, gap);
            GuiHelpers.inlineDotSep();
            ImGui.sameLine(0, gap * 0.5f);
            GuiHelpers.statusBadge("watching",
                    ImGuiTheme.YELLOW_R, ImGuiTheme.YELLOW_G, ImGuiTheme.YELLOW_B);
        }

        // Last-loader-error readout (right-aligned, only when non-empty).
        // Reads bwu_get_last_error() once per frame; the native call is a cheap
        // pointer return so per-frame polling is fine.
        renderBwuError(gap);
    }

    private void renderBwuError(float gap) {
        if (bwu == null) return;
        String err;
        try {
            err = bwu.getLastError();
        } catch (Throwable t) {
            return;
        }
        if (err == null || err.isEmpty()) return;

        String display = err.length() > INLINE_ERR_MAX
                ? err.substring(0, INLINE_ERR_MAX - 1) + "\u2026"
                : err;
        String prefix = "bwu err: ";
        String copyLabel = "Copy##bwu_err";

        // Measure so we can right-align: [prefix+display] [gap] [Copy button]
        float textW = ImGui.calcTextSize(prefix + display).x;
        float btnW = ImGui.calcTextSize(copyLabel).x
                + ImGui.getStyle().getFramePaddingX() * 2f;
        float needed = textW + gap + btnW;
        float avail = ImGui.getContentRegionAvailX();

        ImGui.sameLine(0, Math.max(gap, avail - needed));
        ImGui.textColored(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 1f,
                prefix + display);
        if (ImGui.isItemHovered()) {
            ImGui.beginTooltip();
            ImGui.pushTextWrapPos(ImGui.getFontSize() * 40f);
            ImGui.textUnformatted(err);
            ImGui.popTextWrapPos();
            ImGui.endTooltip();
        }
        ImGui.sameLine(0, gap * 0.5f);
        if (ImGui.smallButton(copyLabel)) {
            ClipboardHelper.copyToClipboard(err);
        }
    }
}
