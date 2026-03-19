package com.botwithus.bot.cli.gui;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;

/**
 * Reusable styled UI helpers for consistent visual elements across panels.
 */
public final class GuiHelpers {

    private GuiHelpers() {}

    /**
     * Render a styled section header with an accent left-border bar.
     */
    public static void sectionHeader(String text) {
        ImGui.spacing();
        ImGui.spacing();

        ImDrawList draw = ImGui.getWindowDrawList();
        float cursorX = ImGui.getCursorScreenPosX();
        float cursorY = ImGui.getCursorScreenPosY();
        float textHeight = ImGui.getTextLineHeight();

        // Accent bar on left
        int accentCol = ImGuiTheme.imCol32(ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.8f);
        draw.addRectFilled(cursorX, cursorY, cursorX + 3f, cursorY + textHeight, accentCol, 2f);

        ImGui.setCursorPosX(ImGui.getCursorPosX() + 12f);
        ImGui.textColored(ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.9f, text);

        ImGui.spacing();
    }

    /**
     * Render a small colored status badge with text.
     */
    public static void statusBadge(String text, float r, float g, float b) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImVec2 textSize = new ImVec2();
        ImGui.calcTextSize(textSize, text);

        float padX = 6f;
        float padY = 2f;
        float rounding = 4f;

        // Badge background
        int bgCol = ImGuiTheme.imCol32(r, g, b, 0.15f);
        int borderCol = ImGuiTheme.imCol32(r, g, b, 0.3f);
        draw.addRectFilled(x, y, x + textSize.x + padX * 2, y + textSize.y + padY * 2, bgCol, rounding);
        draw.addRect(x, y, x + textSize.x + padX * 2, y + textSize.y + padY * 2, borderCol, rounding);

        // Text
        int textCol = ImGuiTheme.imCol32(r, g, b, 0.9f);
        draw.addText(x + padX, y + padY, textCol, text);

        // Advance cursor past the badge
        ImGui.dummy(textSize.x + padX * 2, textSize.y + padY * 2);
    }

    /**
     * Render a small dot indicator with color.
     */
    public static void statusDot(float r, float g, float b) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX() + 4f;
        float y = ImGui.getCursorScreenPosY() + ImGui.getTextLineHeight() / 2f;
        int col = ImGuiTheme.imCol32(r, g, b, 1f);
        int glowCol = ImGuiTheme.imCol32(r, g, b, 0.25f);

        // Glow
        draw.addCircleFilled(x, y, 6f, glowCol);
        // Dot
        draw.addCircleFilled(x, y, 3.5f, col);

        ImGui.dummy(12f, ImGui.getTextLineHeight());
    }

    /**
     * Begin a card-like child region with elevated background.
     */
    public static boolean beginCard(String id, float width, float height) {
        ImGui.pushStyleColor(ImGuiCol.ChildBg, ImGuiTheme.SURFACE_R, ImGuiTheme.SURFACE_G, ImGuiTheme.SURFACE_B, 1f);
        ImGui.pushStyleColor(ImGuiCol.Border, ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.4f);
        boolean open = ImGui.beginChild(id, width, height, true);
        ImGui.popStyleColor(2);
        return open;
    }

    /**
     * End a card-like child region.
     */
    public static void endCard() {
        ImGui.endChild();
    }

    /**
     * Render a styled primary button (green accent).
     */
    public static boolean buttonPrimary(String label) {
        ImGui.pushStyleColor(ImGuiCol.Button, ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.25f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.40f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.55f);
        ImGui.pushStyleColor(ImGuiCol.Text, ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 1f);
        boolean clicked = ImGui.button(label);
        ImGui.popStyleColor(4);
        return clicked;
    }

    /**
     * Render a styled danger button (red).
     */
    public static boolean buttonDanger(String label) {
        ImGui.pushStyleColor(ImGuiCol.Button, ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 0.2f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 0.35f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 0.5f);
        ImGui.pushStyleColor(ImGuiCol.Text, ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 1f);
        boolean clicked = ImGui.button(label);
        ImGui.popStyleColor(4);
        return clicked;
    }

    /**
     * Render a styled small danger button (red).
     */
    public static boolean smallButtonDanger(String label) {
        ImGui.pushStyleColor(ImGuiCol.Button, ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 0.15f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 0.3f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 0.45f);
        ImGui.pushStyleColor(ImGuiCol.Text, ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 1f);
        boolean clicked = ImGui.smallButton(label);
        ImGui.popStyleColor(4);
        return clicked;
    }

    /**
     * Render a styled secondary button (subtle).
     */
    public static boolean buttonSecondary(String label) {
        ImGui.pushStyleColor(ImGuiCol.Button, ImGuiTheme.ELEVATED_R, ImGuiTheme.ELEVATED_G, ImGuiTheme.ELEVATED_B, 1f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, ImGuiTheme.ELEVATED_R + 0.05f, ImGuiTheme.ELEVATED_G + 0.05f, ImGuiTheme.ELEVATED_B + 0.05f, 1f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.3f);
        boolean clicked = ImGui.button(label);
        ImGui.popStyleColor(3);
        return clicked;
    }

    /**
     * Render a horizontal separator with less visual weight.
     */
    public static void subtleSeparator() {
        ImGui.pushStyleColor(ImGuiCol.Separator, ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.2f);
        ImGui.separator();
        ImGui.popStyleColor();
    }

    /**
     * Render secondary/muted text.
     */
    public static void textSecondary(String text) {
        ImGui.textColored(ImGuiTheme.TEXT_SEC_R, ImGuiTheme.TEXT_SEC_G, ImGuiTheme.TEXT_SEC_B, 1f, text);
    }

    /**
     * Render muted/dim text.
     */
    public static void textMuted(String text) {
        ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f, text);
    }

    /**
     * Render a compact inline label:value pair.
     */
    public static void labelValue(String label, String value) {
        textSecondary(label);
        ImGui.sameLine(0, 4);
        ImGui.text(value);
    }
}
