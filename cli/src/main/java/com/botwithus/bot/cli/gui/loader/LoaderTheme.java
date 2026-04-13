package com.botwithus.bot.cli.gui.loader;

import com.botwithus.bot.cli.gui.ImGuiTheme;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;

/**
 * Style push/pop for the loader screen. Applies a premium look
 * on top of the base ImGuiTheme.
 */
public final class LoaderTheme {

    private LoaderTheme() {}

    private static final int STYLE_VAR_COUNT = 5;
    private static final int STYLE_COLOR_COUNT = 5;

    /**
     * Push loader-specific styles. Must be paired with {@link #pop()}.
     */
    public static void push() {
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 12f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 6f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 24, 20);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 8, 10);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 12, 10);

        // Slightly adjusted colors for the loader
        ImGui.pushStyleColor(ImGuiCol.WindowBg,
                ImGuiTheme.BG_R, ImGuiTheme.BG_G, ImGuiTheme.BG_B, 1f);
        ImGui.pushStyleColor(ImGuiCol.FrameBg,
                ImGuiTheme.INPUT_BG_R, ImGuiTheme.INPUT_BG_G, ImGuiTheme.INPUT_BG_B, 1f);
        ImGui.pushStyleColor(ImGuiCol.Button,
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.85f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered,
                ImGuiTheme.ACCENT_R * 0.85f, ImGuiTheme.ACCENT_G * 0.85f, ImGuiTheme.ACCENT_B * 0.85f, 1f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive,
                ImGuiTheme.ACCENT_R * 0.7f, ImGuiTheme.ACCENT_G * 0.7f, ImGuiTheme.ACCENT_B * 0.7f, 1f);
    }

    /**
     * Pop loader-specific styles.
     */
    public static void pop() {
        ImGui.popStyleColor(STYLE_COLOR_COUNT);
        ImGui.popStyleVar(STYLE_VAR_COUNT);
    }
}
