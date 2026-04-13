package com.botwithus.bot.cli.gui;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;

/**
 * Shared top bar rendered in both User and Developer modes.
 * Contains branding on the left and a mode toggle on the right.
 */
public class TopBar {

    private static final float BAR_HEIGHT = 40f;

    /**
     * Render the top bar. Returns the new mode if the toggle was clicked, or null if unchanged.
     */
    public AppMode render(AppMode currentMode, float dpiScale) {
        AppMode newMode = null;
        float scaledHeight = BAR_HEIGHT * dpiScale;

        // Top bar background
        ImGui.pushStyleColor(ImGuiCol.ChildBg,
                ImGuiTheme.SIDEBAR_BG_R, ImGuiTheme.SIDEBAR_BG_G, ImGuiTheme.SIDEBAR_BG_B, 1f);
        ImGui.pushStyleColor(ImGuiCol.Border,
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.3f);
        ImGui.beginChild("##topbar", 0, scaledHeight, true);
        ImGui.popStyleColor(2);

        float windowWidth = ImGui.getWindowWidth();
        float paddingX = 12f * dpiScale;
        float centerY = (scaledHeight - ImGui.getTextLineHeight()) / 2f;

        // --- Left: Brand ---
        ImGui.setCursorPos(paddingX, centerY);

        ImDrawList draw = ImGui.getWindowDrawList();
        float logoX = ImGui.getCursorScreenPosX();
        float logoY = ImGui.getCursorScreenPosY();
        int accentCol = ImGuiTheme.imCol32(
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 1f);

        // Brand mark — small accent square
        draw.addRectFilled(logoX, logoY, logoX + 4f * dpiScale,
                logoY + ImGui.getTextLineHeight(), accentCol, 2f);

        ImGui.setCursorPosX(ImGui.getCursorPosX() + 12f * dpiScale);
        ImGui.setCursorPosY(centerY);
        ImGui.pushStyleColor(ImGuiCol.Text,
                ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.95f);
        ImGui.text("BotWithUs");
        ImGui.popStyleColor();

        // --- Right: Mode toggle ---
        float toggleWidth = 130f * dpiScale;
        float toggleX = windowWidth - toggleWidth - paddingX;
        float buttonHeight = ImGui.getFrameHeight();
        float toggleY = (scaledHeight - buttonHeight) / 2f;

        ImGui.setCursorPos(toggleX, toggleY);

        boolean isUser = (currentMode == AppMode.USER);

        // "User" button
        float halfWidth = (toggleWidth - 4f * dpiScale) / 2f;
        if (isUser) {
            ImGui.pushStyleColor(ImGuiCol.Button,
                    ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.25f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered,
                    ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.35f);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive,
                    ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.45f);
            ImGui.pushStyleColor(ImGuiCol.Text,
                    ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 1f);
        } else {
            ImGui.pushStyleColor(ImGuiCol.Button, 0f, 0f, 0f, 0f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered,
                    ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.05f);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive,
                    ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.08f);
            ImGui.pushStyleColor(ImGuiCol.Text,
                    ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f);
        }

        if (ImGui.button(Icons.USERS + " User##modeUser", halfWidth, buttonHeight)) {
            newMode = AppMode.USER;
        }
        ImGui.popStyleColor(4);

        ImGui.sameLine(0, 4f * dpiScale);

        // "Dev" button
        boolean isDev = (currentMode == AppMode.DEVELOPER);
        if (isDev) {
            ImGui.pushStyleColor(ImGuiCol.Button,
                    ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.25f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered,
                    ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.35f);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive,
                    ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.45f);
            ImGui.pushStyleColor(ImGuiCol.Text,
                    ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 1f);
        } else {
            ImGui.pushStyleColor(ImGuiCol.Button, 0f, 0f, 0f, 0f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered,
                    ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.05f);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive,
                    ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.08f);
            ImGui.pushStyleColor(ImGuiCol.Text,
                    ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f);
        }

        if (ImGui.button(Icons.CODE + " Dev##modeDev", halfWidth, buttonHeight)) {
            newMode = AppMode.DEVELOPER;
        }
        ImGui.popStyleColor(4);

        // F12 hint text
        ImGui.sameLine(0, 8f * dpiScale);
        ImGui.setCursorPosY(toggleY + (buttonHeight - ImGui.getTextLineHeight()) / 2f);
        ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 0.4f,
                "F12");

        ImGui.endChild();

        return newMode;
    }
}
