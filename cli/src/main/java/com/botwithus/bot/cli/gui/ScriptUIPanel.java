package com.botwithus.bot.cli.gui;

import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import imgui.ImGui;
import imgui.flag.ImGuiTabBarFlags;

import java.util.List;

/**
 * Panel that renders custom script UIs.
 * Each loaded script with a {@link ScriptUI} gets its own tab.
 */
public class ScriptUIPanel implements GuiPanel {

    @Override
    public String title() {
        return "Script UI";
    }

    @Override
    public void render(CliContext ctx) {
        var connections = ctx.getConnections();
        if (connections.isEmpty()) {
            ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f,
                    "No active connections.");
            return;
        }

        boolean hasAnyUI = false;

        if (ImGui.beginTabBar("##scriptUITabs", ImGuiTabBarFlags.None)) {
            for (Connection conn : connections) {
                ScriptRuntime runtime = conn.getRuntime();
                List<ScriptRunner> runners = runtime.getRunners();

                for (ScriptRunner runner : runners) {
                    ScriptUI scriptUI = runner.getScript().getUI();
                    if (scriptUI == null) continue;

                    hasAnyUI = true;
                    String tabLabel = runner.getScriptName();
                    if (connections.size() > 1) {
                        tabLabel += " [" + conn.getName() + "]";
                    }

                    if (ImGui.beginTabItem(tabLabel)) {
                        ImGui.pushID(conn.getName() + "/" + runner.getScriptName());
                        try {
                            scriptUI.render();
                        } catch (Exception e) {
                            ImGui.textColored(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 1f,
                                    "Error rendering script UI: " + e.getMessage());
                        }
                        ImGui.popID();
                        ImGui.endTabItem();
                    }
                }
            }
            ImGui.endTabBar();
        }

        if (!hasAnyUI) {
            ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f,
                    "No scripts with custom UI are loaded. Scripts can provide UI by implementing ScriptUI and overriding getUI().");
        }
    }
}
