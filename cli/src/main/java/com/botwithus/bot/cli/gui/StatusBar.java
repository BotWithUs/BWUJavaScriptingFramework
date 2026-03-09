package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.core.runtime.ScriptRunner;

import imgui.ImGui;
import imgui.flag.ImGuiCol;

/**
 * Fixed status bar rendered at the bottom of the window, always visible regardless of active tab.
 */
public class StatusBar {

    public void render(CliContext ctx) {
        ImGui.separator();

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

        // Connection indicator
        if (connected) {
            ImGui.textColored(ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B, 1f, "\u25CF");
        } else {
            ImGui.textColored(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 1f, "\u25CF");
        }

        ImGui.sameLine(0, 6);

        // Active connection name
        if (activeName != null) {
            ImGui.textColored(ImGuiTheme.CYAN_R, ImGuiTheme.CYAN_G, ImGuiTheme.CYAN_B, 1f, activeName);
        } else {
            ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f, "disconnected");
        }

        ImGui.sameLine(0, 12);
        ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f, "|");
        ImGui.sameLine(0, 12);

        // Connection count
        ImGui.text(connCount + " conn");

        // Mounted status
        if (mounted) {
            ImGui.sameLine(0, 12);
            ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f, "|");
            ImGui.sameLine(0, 12);
            ImGui.textColored(ImGuiTheme.MAGENTA_R, ImGuiTheme.MAGENTA_G, ImGuiTheme.MAGENTA_B, 1f,
                    "mounted:" + mountedName);
        }

        ImGui.sameLine(0, 12);
        ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f, "|");
        ImGui.sameLine(0, 12);

        // Running scripts count
        if (runningScripts > 0) {
            ImGui.textColored(ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B, 1f,
                    runningScripts + " script" + (runningScripts != 1 ? "s" : "") + " running");
        } else {
            ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f,
                    "no scripts");
        }

        // Watcher status
        if (watcherRunning) {
            ImGui.sameLine(0, 12);
            ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f, "|");
            ImGui.sameLine(0, 12);
            ImGui.textColored(ImGuiTheme.YELLOW_R, ImGuiTheme.YELLOW_G, ImGuiTheme.YELLOW_B, 1f, "watching");
        }
    }
}
