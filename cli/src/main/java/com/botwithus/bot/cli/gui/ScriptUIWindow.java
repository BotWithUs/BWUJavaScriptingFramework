package com.botwithus.bot.cli.gui;

import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.core.runtime.ScriptRunner;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;

public class ScriptUIWindow {

    /** Default width (px) for the script custom-UI window. */
    private static final int CUSTOM_UI_WINDOW_DEFAULT_WIDTH = 925;
    /** Default height (px) for the script custom-UI window. */
    private static final int CUSTOM_UI_WINDOW_DEFAULT_HEIGHT = 690;

    private ScriptRunner runner;
    private final ImBoolean open = new ImBoolean(false);

    public void open(ScriptRunner runner) {
        this.runner = runner;
        open.set(true);
        ImGui.setNextWindowSize(CUSTOM_UI_WINDOW_DEFAULT_WIDTH, CUSTOM_UI_WINDOW_DEFAULT_HEIGHT, ImGuiCond.FirstUseEver);
    }

    public boolean isOpen() {
        return open.get();
    }

    public void render() {
        if (!open.get() || runner == null) {
            return;
        }
        if (runner.isDisposed()) {
            open.set(false);
            runner = null;
            return;
        }

        ScriptUI ui = runner.getScript().getUI();
        if (ui == null) {
            open.set(false);
            return;
        }

        ImGui.setNextWindowSize(CUSTOM_UI_WINDOW_DEFAULT_WIDTH, CUSTOM_UI_WINDOW_DEFAULT_HEIGHT, ImGuiCond.FirstUseEver);
        if (ImGui.begin(runner.getScriptName() + " Config###scriptUIWindow", open,
                ImGuiWindowFlags.NoCollapse)) {
            try {
                ui.render();
            } catch (Exception e) {
                ImGui.textColored(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 1f,
                        "UI error: " + e.getMessage());
            }
        }
        ImGui.end();
    }
}
