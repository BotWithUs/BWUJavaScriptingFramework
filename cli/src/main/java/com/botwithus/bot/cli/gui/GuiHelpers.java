package com.botwithus.bot.cli.gui;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;

/**
 * Reusable styled UI helpers for consistent visual elements across panels.
 *
 * All sizes are derived from the current font size, frame height, or
 * style spacing so the UI scales correctly with DPI and theme changes.
 */
public final class GuiHelpers {

    private GuiHelpers() {}

    /** Accent bar width for section headers, expressed relative to font size. */
    private static final float ACCENT_BAR_FRACTION = 0.18f;

    // ─────────────────────────────────────────────────────────────────────
    // Section headers & dividers
    // ─────────────────────────────────────────────────────────────────────

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
        float barWidth = Math.max(2f, textHeight * ACCENT_BAR_FRACTION);
        float gap = ImGui.getStyle().getItemInnerSpacingX() * 1.5f;

        int accentCol = ImGuiTheme.imCol32(
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.8f);
        draw.addRectFilled(cursorX, cursorY, cursorX + barWidth, cursorY + textHeight,
                accentCol, barWidth * 0.5f);

        ImGui.setCursorPosX(ImGui.getCursorPosX() + barWidth + gap);
        ImGui.textColored(ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.92f, text);

        ImGui.spacing();
    }

    /**
     * Centered horizontal divider with a subtle label. Use as a soft section break.
     */
    public static void sectionDivider(String label) {
        ImGui.spacing();
        ImDrawList draw = ImGui.getWindowDrawList();
        float availW = ImGui.getContentRegionAvailX();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY() + ImGui.getTextLineHeight() * 0.5f;

        ImVec2 textSize = new ImVec2();
        ImGui.calcTextSize(textSize, label);

        float gap = ImGui.getStyle().getItemSpacingX() * 1.5f;
        float textX = x + (availW - textSize.x) * 0.5f;

        int lineCol = ImGuiTheme.imCol32(
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.35f);

        draw.addLine(x, y, textX - gap, y, lineCol, 1f);
        draw.addLine(textX + textSize.x + gap, y, x + availW, y, lineCol, 1f);

        ImGui.setCursorPosX(ImGui.getCursorPosX() + (availW - textSize.x) * 0.5f);
        ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 0.75f,
                label);
        ImGui.spacing();
    }

    /**
     * Render a horizontal separator with less visual weight.
     */
    public static void subtleSeparator() {
        ImGui.pushStyleColor(ImGuiCol.Separator,
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.2f);
        ImGui.separator();
        ImGui.popStyleColor();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Badges, chips, dots
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Render a small colored status badge with text.
     */
    public static void statusBadge(String text, float r, float g, float b) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImVec2 textSize = new ImVec2();
        ImGui.calcTextSize(textSize, text);

        float fontH = ImGui.getFontSize();
        float padX = fontH * 0.4f;
        float padY = fontH * 0.12f;
        float rounding = fontH * 0.25f;

        int bgCol = ImGuiTheme.imCol32(r, g, b, 0.15f);
        int borderCol = ImGuiTheme.imCol32(r, g, b, 0.35f);
        draw.addRectFilled(x, y, x + textSize.x + padX * 2, y + textSize.y + padY * 2,
                bgCol, rounding);
        draw.addRect(x, y, x + textSize.x + padX * 2, y + textSize.y + padY * 2,
                borderCol, rounding);

        int textCol = ImGuiTheme.imCol32(r, g, b, 0.92f);
        draw.addText(x + padX, y + padY, textCol, text);

        ImGui.dummy(textSize.x + padX * 2, textSize.y + padY * 2);
    }

    /**
     * Compact metric chip: dim label + bright value. Used inline in status bars.
     * Advances the cursor on {@code sameLine()}, never breaks to a new row.
     */
    public static void metricChip(String label, String value, float r, float g, float b) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();

        ImVec2 labelSize = new ImVec2();
        ImGui.calcTextSize(labelSize, label);
        ImVec2 valueSize = new ImVec2();
        ImGui.calcTextSize(valueSize, value);

        float fontH = ImGui.getFontSize();
        float padX = fontH * 0.45f;
        float padY = fontH * 0.10f;
        float gap = fontH * 0.3f;
        float rounding = fontH * 0.25f;

        float totalW = labelSize.x + gap + valueSize.x + padX * 2;
        float totalH = Math.max(labelSize.y, valueSize.y) + padY * 2;

        int bgCol = ImGuiTheme.imCol32(r, g, b, 0.08f);
        int accentCol = ImGuiTheme.imCol32(r, g, b, 0.85f);
        int labelCol = ImGuiTheme.imCol32(
                ImGuiTheme.TEXT_SEC_R, ImGuiTheme.TEXT_SEC_G, ImGuiTheme.TEXT_SEC_B, 0.85f);

        draw.addRectFilled(x, y, x + totalW, y + totalH, bgCol, rounding);
        draw.addText(x + padX, y + padY, labelCol, label);
        draw.addText(x + padX + labelSize.x + gap, y + padY, accentCol, value);

        ImGui.dummy(totalW, totalH);
    }

    /**
     * Render a small dot indicator with a soft glow.
     */
    public static void statusDot(float r, float g, float b) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float fontH = ImGui.getFontSize();
        float dotRadius = fontH * 0.22f;
        float glowRadius = dotRadius * 1.7f;
        float x = ImGui.getCursorScreenPosX() + dotRadius + 2f;
        float y = ImGui.getCursorScreenPosY() + ImGui.getTextLineHeight() / 2f;
        int col = ImGuiTheme.imCol32(r, g, b, 1f);
        int glowCol = ImGuiTheme.imCol32(r, g, b, 0.25f);

        draw.addCircleFilled(x, y, glowRadius, glowCol);
        draw.addCircleFilled(x, y, dotRadius, col);

        ImGui.dummy(glowRadius * 2f + 2f, ImGui.getTextLineHeight());
    }

    /**
     * Pulsing dot for live/active indicators (running scripts, active connection).
     * The glow radius breathes at ~1Hz so the eye registers activity.
     */
    public static void pulsingDot(float r, float g, float b) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float fontH = ImGui.getFontSize();
        float dotRadius = fontH * 0.22f;
        float pulseAmount = Motion.pulse(1.0) * 0.6f + 0.4f; // 0.4..1.0
        float glowRadius = dotRadius * (1.5f + pulseAmount * 1.1f);
        float x = ImGui.getCursorScreenPosX() + glowRadius;
        float y = ImGui.getCursorScreenPosY() + ImGui.getTextLineHeight() / 2f;

        int col = ImGuiTheme.imCol32(r, g, b, 1f);
        int glowCol = ImGuiTheme.imCol32(r, g, b, 0.08f + pulseAmount * 0.22f);

        draw.addCircleFilled(x, y, glowRadius, glowCol);
        draw.addCircleFilled(x, y, dotRadius, col);

        ImGui.dummy(glowRadius * 2f, ImGui.getTextLineHeight());
    }

    /**
     * A compact keyboard-shortcut badge, e.g. [F12] or [Ctrl+K].
     */
    public static void kbdHint(String keys) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImVec2 s = new ImVec2();
        ImGui.calcTextSize(s, keys);

        float fontH = ImGui.getFontSize();
        float padX = fontH * 0.3f;
        float padY = fontH * 0.08f;
        float rounding = fontH * 0.2f;

        int bg = ImGuiTheme.imCol32(
                ImGuiTheme.ELEVATED_R, ImGuiTheme.ELEVATED_G, ImGuiTheme.ELEVATED_B, 0.6f);
        int border = ImGuiTheme.imCol32(
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.5f);
        int text = ImGuiTheme.imCol32(
                ImGuiTheme.TEXT_SEC_R, ImGuiTheme.TEXT_SEC_G, ImGuiTheme.TEXT_SEC_B, 0.75f);

        draw.addRectFilled(x, y, x + s.x + padX * 2, y + s.y + padY * 2, bg, rounding);
        draw.addRect(x, y, x + s.x + padX * 2, y + s.y + padY * 2, border, rounding);
        draw.addText(x + padX, y + padY, text, keys);

        ImGui.dummy(s.x + padX * 2, s.y + padY * 2);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Cards
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Begin a card-like child region with elevated background.
     */
    public static boolean beginCard(String id, float width, float height) {
        ImGui.pushStyleColor(ImGuiCol.ChildBg,
                ImGuiTheme.SURFACE_R, ImGuiTheme.SURFACE_G, ImGuiTheme.SURFACE_B, 1f);
        ImGui.pushStyleColor(ImGuiCol.Border,
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.4f);
        boolean open = ImGui.beginChild(id, width, height, true);
        ImGui.popStyleColor(2);
        return open;
    }

    public static void endCard() {
        ImGui.endChild();
    }

    /**
     * Render a compact hero stat card with an icon, label, and value.
     *
     * Draws a custom background with a subtle vertical gradient and a
     * thin accent bar on the left. Sized relative to the font so it
     * scales with DPI.
     */
    public static void statCard(float width, String icon, String label, String value,
                                float r, float g, float b) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float fontH = ImGui.getFontSize();
        float padX = fontH * 0.9f;
        float padY = fontH * 0.65f;
        float height = fontH * 3.8f;

        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float rounding = fontH * 0.35f;
        float barWidth = fontH * 0.18f;

        int bgTop = ImGuiTheme.imCol32(
                ImGuiTheme.SURFACE_R, ImGuiTheme.SURFACE_G, ImGuiTheme.SURFACE_B, 1f);
        int bgBottom = ImGuiTheme.imCol32(
                ImGuiTheme.INPUT_BG_R, ImGuiTheme.INPUT_BG_G, ImGuiTheme.INPUT_BG_B, 1f);
        int border = ImGuiTheme.imCol32(
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.35f);
        int accent = ImGuiTheme.imCol32(r, g, b, 0.9f);
        int accentSoft = ImGuiTheme.imCol32(r, g, b, 0.05f);
        int labelCol = ImGuiTheme.imCol32(
                ImGuiTheme.TEXT_SEC_R, ImGuiTheme.TEXT_SEC_G, ImGuiTheme.TEXT_SEC_B, 0.85f);
        int valueCol = ImGuiTheme.imCol32(
                ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 1f);
        int iconCol = ImGuiTheme.imCol32(r, g, b, 0.85f);

        // Gradient background
        draw.addRectFilledMultiColor(x, y, x + width, y + height, bgTop, bgTop, bgBottom, bgBottom);
        // Soft accent wash on top-right
        draw.addRectFilledMultiColor(x, y, x + width, y + height,
                accentSoft, ImGuiTheme.imCol32(r, g, b, 0.12f), accentSoft, accentSoft);
        // Border
        draw.addRect(x, y, x + width, y + height, border, rounding);
        // Accent bar
        draw.addRectFilled(x, y, x + barWidth, y + height, accent, rounding * 0.5f);

        // Label
        draw.addText(x + padX + fontH * 1.8f, y + padY, labelCol, label);
        // Value (same X, slightly below)
        draw.addText(x + padX + fontH * 1.8f, y + padY + fontH * 1.3f, valueCol, value);

        // Icon (larger, accent-tinted)
        draw.addText(x + padX, y + padY + fontH * 0.6f, iconCol, icon);

        ImGui.dummy(width, height);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Buttons
    // ─────────────────────────────────────────────────────────────────────

    public static boolean buttonPrimary(String label) {
        return buttonPrimary(label, 0f, 0f);
    }

    public static boolean buttonPrimary(String label, float width, float height) {
        ImGui.pushStyleColor(ImGuiCol.Button, ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.25f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.40f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.55f);
        ImGui.pushStyleColor(ImGuiCol.Text, ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 1f);
        boolean clicked = ImGui.button(label, width, height);
        ImGui.popStyleColor(4);
        return clicked;
    }

    public static boolean buttonDanger(String label) {
        return buttonDanger(label, 0f, 0f);
    }

    public static boolean buttonDanger(String label, float width, float height) {
        ImGui.pushStyleColor(ImGuiCol.Button, ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 0.2f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 0.35f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 0.5f);
        ImGui.pushStyleColor(ImGuiCol.Text, ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 1f);
        boolean clicked = ImGui.button(label, width, height);
        ImGui.popStyleColor(4);
        return clicked;
    }

    public static boolean smallButtonDanger(String label) {
        ImGui.pushStyleColor(ImGuiCol.Button, ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 0.15f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 0.3f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 0.45f);
        ImGui.pushStyleColor(ImGuiCol.Text, ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 1f);
        boolean clicked = ImGui.smallButton(label);
        ImGui.popStyleColor(4);
        return clicked;
    }

    public static boolean buttonSecondary(String label) {
        return buttonSecondary(label, 0f, 0f);
    }

    public static boolean buttonSecondary(String label, float width, float height) {
        ImGui.pushStyleColor(ImGuiCol.Button, ImGuiTheme.ELEVATED_R, ImGuiTheme.ELEVATED_G, ImGuiTheme.ELEVATED_B, 1f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, ImGuiTheme.ELEVATED_R + 0.05f, ImGuiTheme.ELEVATED_G + 0.05f, ImGuiTheme.ELEVATED_B + 0.05f, 1f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.3f);
        boolean clicked = ImGui.button(label, width, height);
        ImGui.popStyleColor(3);
        return clicked;
    }

    /**
     * Custom pill-style toggle switch. More tactile and easier to scan than
     * the default checkbox. Animates the knob position with a per-ID spring.
     *
     * @return true if the toggle was clicked this frame
     */
    public static boolean toggleSwitch(String id, boolean value) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float fontH = ImGui.getFontSize();
        float height = fontH * 1.05f;
        float width = height * 1.9f;
        float knobRadius = height * 0.42f;

        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY() + (ImGui.getTextLineHeight() - height) * 0.5f;

        ImGui.invisibleButton(id, width, height);
        boolean clicked = ImGui.isItemClicked();
        boolean hovered = ImGui.isItemHovered();

        float t = Motion.step("sw:" + id, value ? 1f : 0f, 16f);

        int trackOff = ImGuiTheme.imCol32(
                ImGuiTheme.INPUT_BG_R, ImGuiTheme.INPUT_BG_G, ImGuiTheme.INPUT_BG_B, 1f);
        int trackOn = ImGuiTheme.imCol32(
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.6f);
        int trackCol = lerpColor(trackOff, trackOn, t);
        int knobCol = ImGuiTheme.imCol32(
                ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, hovered ? 1f : 0.92f);
        int border = ImGuiTheme.imCol32(
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.5f);

        draw.addRectFilled(x, y, x + width, y + height, trackCol, height * 0.5f);
        draw.addRect(x, y, x + width, y + height, border, height * 0.5f);

        float knobMinX = x + knobRadius + height * 0.1f;
        float knobMaxX = x + width - knobRadius - height * 0.1f;
        float knobX = knobMinX + (knobMaxX - knobMinX) * t;
        float knobY = y + height * 0.5f;

        // Soft shadow below knob
        int shadow = ImGuiTheme.imCol32(0f, 0f, 0f, 0.25f);
        draw.addCircleFilled(knobX, knobY + 1f, knobRadius, shadow);
        draw.addCircleFilled(knobX, knobY, knobRadius, knobCol);

        return clicked;
    }

    private static int lerpColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = a & 0xFF, ag = (a >>> 8) & 0xFF, ab = (a >>> 16) & 0xFF, aa = (a >>> 24) & 0xFF;
        int br = b & 0xFF, bg = (b >>> 8) & 0xFF, bb = (b >>> 16) & 0xFF, ba = (b >>> 24) & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        int al = (int) (aa + (ba - aa) * t);
        return (al << 24) | (bl << 16) | (g << 8) | r;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Text helpers
    // ─────────────────────────────────────────────────────────────────────

    public static void textSecondary(String text) {
        ImGui.textColored(ImGuiTheme.TEXT_SEC_R, ImGuiTheme.TEXT_SEC_G, ImGuiTheme.TEXT_SEC_B, 1f, text);
    }

    public static void textMuted(String text) {
        ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f, text);
    }

    public static void labelValue(String label, String value) {
        textSecondary(label);
        ImGui.sameLine(0, ImGui.getStyle().getItemInnerSpacingX());
        ImGui.text(value);
    }

    /**
     * Draw a subtle inline dot separator, sized from font metrics.
     * Replaces the visual-noise `|` character used previously.
     */
    public static void inlineDotSep() {
        ImDrawList draw = ImGui.getWindowDrawList();
        float fontH = ImGui.getFontSize();
        float r = Math.max(1.5f, fontH * 0.11f);
        float x = ImGui.getCursorScreenPosX() + r + 2f;
        float y = ImGui.getCursorScreenPosY() + ImGui.getTextLineHeight() * 0.5f;
        int col = ImGuiTheme.imCol32(
                ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 0.5f);
        draw.addCircleFilled(x, y, r, col);
        ImGui.dummy(r * 2f + 4f, ImGui.getTextLineHeight());
    }
}
