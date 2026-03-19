package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.core.runtime.ScriptRunner;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;

/**
 * Fixed status bar rendered at the bottom of the window, always visible regardless of active tab.
 */
public class StatusBar {

    public void render(CliContext ctx) {
        // Draw a subtle top border
        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float w = ImGui.getContentRegionAvailX();
        int borderCol = ImGuiTheme.imCol32(ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.3f);
        draw.addLine(x, y, x + w, y, borderCol);

        ImGui.spacing();

        boolean connected = ctx.hasActiveConnection();
        String activeName = ctx.getActiveConnectionName();
        int connCount = ctx.getConnections().size();
        boolean mounted = ctx.isMounted();
        String mountedName = ctx.getMountedConnectionName();
        boolean watcherRunning = ctx.isWatcherRunning();

        // Count running scripts across all connections
        int runningScripts = 0;
        for (Connection conn : ctx.getConnections()) {
            for (ScriptRunner runner : conn.getRuntime().getRunners()) {
                if (runner.isRunning()) runningScripts++;
            }
        }

        // Connection status with dot indicator
        if (connected) {
            GuiHelpers.statusDot(ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B);
        } else {
            GuiHelpers.statusDot(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B);
        }
        ImGui.sameLine(0, 4);

        // Active connection name
        if (activeName != null) {
            ImGui.textColored(ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.9f, activeName);
        } else {
            GuiHelpers.textMuted("disconnected");
        }

        // Separator
        ImGui.sameLine(0, 16);
        GuiHelpers.textMuted("|");
        ImGui.sameLine(0, 16);

        // Connection count
        GuiHelpers.textSecondary(connCount + " conn");

        // Mounted status
        if (mounted) {
            ImGui.sameLine(0, 16);
            GuiHelpers.textMuted("|");
            ImGui.sameLine(0, 16);
            GuiHelpers.statusBadge("mounted:" + mountedName,
                    ImGuiTheme.MAGENTA_R, ImGuiTheme.MAGENTA_G, ImGuiTheme.MAGENTA_B);
        }

        ImGui.sameLine(0, 16);
        GuiHelpers.textMuted("|");
        ImGui.sameLine(0, 16);

        // Running scripts
        if (runningScripts > 0) {
            GuiHelpers.statusBadge(runningScripts + " script" + (runningScripts != 1 ? "s" : ""),
                    ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B);
        } else {
            GuiHelpers.textMuted("no scripts");
        }

        // Watcher status
        if (watcherRunning) {
            ImGui.sameLine(0, 16);
            GuiHelpers.textMuted("|");
            ImGui.sameLine(0, 16);
            GuiHelpers.statusBadge("watching",
                    ImGuiTheme.YELLOW_R, ImGuiTheme.YELLOW_G, ImGuiTheme.YELLOW_B);
        }
    }
}
