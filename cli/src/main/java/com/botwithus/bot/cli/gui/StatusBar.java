package com.botwithus.bot.cli.gui;

import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.core.runtime.ScriptRunner;

import imgui.ImDrawList;
import imgui.ImGui;

/**
 * Fixed status bar rendered at the bottom of the window, always visible regardless of active tab.
 *
 * Layout (left → right):
 *   [pulsing dot] [connection name] • [conn chip] • [scripts chip] • [mounted badge?] • [watching badge?]
 *
 * Visuals are derived from font size / style spacing so the bar scales cleanly with DPI.
 */
public class StatusBar {

    public StatusBar() {}

    public void render(CliContext ctx) {
        ImDrawList draw = ImGui.getWindowDrawList();
        renderTopBorder(draw);
        ImGui.dummy(0f, ImGui.getFontSize() * 0.25f);

        float gap = ImGui.getStyle().getItemSpacingX();
        boolean connected = ctx.hasActiveConnection();
        String activeName = ctx.getActiveConnectionName();

        renderActiveConnectionStatus(connected, activeName, activeReconnectState(ctx), gap);
        renderConnectionCountChip(ctx.getConnections().size(), gap);
        renderRunningScriptsChip(countRunningScripts(ctx), gap);
        if (ctx.isMounted()) {
            renderMountedBadge(ctx.getMountedConnectionName(), gap);
        }
        if (ctx.isWatcherRunning()) {
            renderWatcherBadge(gap);
        }
    }

    /** Soft gradient top border: transparent at the edges, dim in the middle. */
    private static void renderTopBorder(ImDrawList draw) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float w = ImGui.getContentRegionAvailX();
        int edge = ImGuiTheme.imCol32(
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0f);
        int mid = ImGuiTheme.imCol32(
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.45f);
        float half = w * 0.5f;
        draw.addRectFilledMultiColor(x, y, x + half, y + 1f, edge, mid, mid, edge);
        draw.addRectFilledMultiColor(x + half, y, x + w, y + 1f, mid, edge, edge, mid);
    }

    private static int countRunningScripts(CliContext ctx) {
        int runningScripts = 0;
        for (Connection conn : ctx.getConnections()) {
            for (ScriptRunner runner : conn.getRuntime().getRunners()) {
                if (runner.isRunning()) {
                    runningScripts++;
                }
            }
        }
        return runningScripts;
    }

    private static void renderConnectionCountChip(int connCount, float gap) {
        ImGui.sameLine(0, gap);
        GuiHelpers.inlineDotSep();
        ImGui.sameLine(0, gap * 0.5f);
        GuiHelpers.metricChip("conn", String.valueOf(connCount),
                ImGuiTheme.TEXT_SEC_R, ImGuiTheme.TEXT_SEC_G, ImGuiTheme.TEXT_SEC_B);
    }

    private static void renderRunningScriptsChip(int runningScripts, float gap) {
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
    }

    private static void renderMountedBadge(String mountedName, float gap) {
        ImGui.sameLine(0, gap);
        GuiHelpers.inlineDotSep();
        ImGui.sameLine(0, gap * 0.5f);
        GuiHelpers.statusBadge("mounted · " + mountedName,
                ImGuiTheme.MAGENTA_R, ImGuiTheme.MAGENTA_G, ImGuiTheme.MAGENTA_B);
    }

    private static void renderWatcherBadge(float gap) {
        ImGui.sameLine(0, gap);
        GuiHelpers.inlineDotSep();
        ImGui.sameLine(0, gap * 0.5f);
        GuiHelpers.statusBadge("watching",
                ImGuiTheme.YELLOW_R, ImGuiTheme.YELLOW_G, ImGuiTheme.YELLOW_B);
    }

    /**
     * Returns the active connection's most recent {@link ReconnectState},
     * or {@code null} if there is no active connection or its controller
     * is not attached.
     */
    private static ReconnectState activeReconnectState(CliContext ctx) {
        Connection active = ctx.getActiveConnection();
        return active != null ? active.currentReconnectState() : null;
    }

    /**
     * Renders the leftmost status segment (dot + label). Dispatches over the
     * sealed {@link ReconnectState} so the compiler refuses to build if a new
     * variant lands without matching coverage here.
     */
    private static void renderActiveConnectionStatus(boolean connected, String activeName,
                                                     ReconnectState reconnect, float gap) {
        if (!connected) {
            renderDisconnected(gap);
            return;
        }
        if (reconnect == null) {
            renderConnectedNormal(activeName, gap);
            return;
        }
        switch (reconnect) {
            case ReconnectState.Reconnecting r -> renderReconnecting(activeName, r, gap);
            case ReconnectState.GivingUp gu -> renderGivingUp(activeName, gap);
            case ReconnectState.Connected c -> renderConnectedNormal(activeName, gap);
            case ReconnectState.Disconnected d -> renderConnectedNormal(activeName, gap);
        }
    }

    private static void renderConnectedNormal(String activeName, float gap) {
        GuiHelpers.pulsingDot(ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B);
        ImGui.sameLine(0, gap * 0.5f);
        ImGui.textColored(ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.95f,
                activeName != null ? activeName : "connected");
    }

    private static void renderReconnecting(String activeName, ReconnectState.Reconnecting r, float gap) {
        GuiHelpers.pulsingDot(ImGuiTheme.YELLOW_R, ImGuiTheme.YELLOW_G, ImGuiTheme.YELLOW_B);
        ImGui.sameLine(0, gap * 0.5f);
        ImGui.textColored(ImGuiTheme.YELLOW_R, ImGuiTheme.YELLOW_G, ImGuiTheme.YELLOW_B, 0.95f,
                "reconnecting " + (activeName != null ? activeName : "") + " (attempt " + r.attempt() + ")");
    }

    private static void renderGivingUp(String activeName, float gap) {
        GuiHelpers.statusDot(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B);
        ImGui.sameLine(0, gap * 0.5f);
        ImGui.textColored(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 0.95f,
                "gave up on " + (activeName != null ? activeName : "connection"));
    }

    private static void renderDisconnected(float gap) {
        GuiHelpers.statusDot(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B);
        ImGui.sameLine(0, gap * 0.5f);
        GuiHelpers.textMuted("disconnected");
    }

}
