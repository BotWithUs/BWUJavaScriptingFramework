package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImString;

/**
 * The Settings page's text box: the token input frame with an optional unit
 * inside it on the right, which reports when a value is committed (Enter, or
 * leaving the box after typing) rather than on every keystroke; its masked form
 * for secrets; and the Integrations grid's tick box. The rest of the page's
 * controls come from {@link Controls}.
 */
final class SettingsWidgets {

    /** What a box did this frame. */
    record Field(boolean isCommitted, boolean isActive) {}

    private static final int BARE_COLOURS = 5;
    private static final int BARE_VARS = 2;
    private static final float BOX_EM = 1.067f;
    private static final float BOX_RADIUS_PX = 3f;
    private static final float TICK_STROKE_PX = 1.8f;
    private static final float TICK_START_X = 0.24f;
    private static final float TICK_START_Y = 0.5f;
    private static final float TICK_MID_X = 0.42f;
    private static final float TICK_MID_Y = 0.68f;
    private static final float TICK_END_X = 0.76f;
    private static final float TICK_END_Y = 0.32f;
    private static final float DISABLED_TICK_ALPHA = 0.25f;
    private static final String NO_HINT = "";

    private final Controls ui;

    SettingsWidgets(Controls ui) {
        this.ui = ui;
    }

    /**
     * A text box at the cursor.
     *
     * @param unit     drawn inside the frame on the right, or empty
     * @param hasError draws the frame in the danger colour
     */
    Field field(String id, ImString buffer, String unit, float width, float height, boolean hasError) {
        return box(id, buffer, unit, NO_HINT, ImGuiInputTextFlags.EnterReturnsTrue, width, height, hasError);
    }

    /**
     * A text box at the cursor with grey {@code hint} text while it is empty.
     *
     * @param isMasked draws the text as dots, for a secret
     */
    Field hintedField(String id, ImString buffer, String hint, boolean isMasked, float width, float height,
                      boolean hasError) {
        int flags = ImGuiInputTextFlags.EnterReturnsTrue | (isMasked ? ImGuiInputTextFlags.Password : 0);
        return box(id, buffer, "", hint, flags, width, height, hasError);
    }

    private Field box(String id, ImString buffer, String unit, String hint, int flags, float width, float height,
                      boolean hasError) {
        ImGuiTheme.Metrics m = ui.m();
        ImFont font = ui.fonts().monoSmall();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + height, ImGuiTheme.COL_BG, m.radius());
        float padX = m.u(3);
        float unitW = unit.isEmpty() ? 0f : ui.width(ui.fonts().caption(), unit) + padX;
        ImGui.setCursorScreenPos(x + padX, y);
        pushBare(font, height);
        ImGui.setNextItemWidth(width - padX * 2f - unitW);
        boolean isEnter = ImGui.inputTextWithHint("##" + id, hint, buffer, flags);
        boolean isCommitted = isEnter || ImGui.isItemDeactivatedAfterEdit();
        boolean isActive = ImGui.isItemActive();
        popBare();
        if (!unit.isEmpty()) {
            ui.textCentredY(draw, ui.fonts().caption(), x + width - padX - ui.width(ui.fonts().caption(), unit),
                    y, height, ImGuiTheme.COL_FG3, unit);
        }
        boolean isHovered = ImGui.isMouseHoveringRect(x, y, x + width, y + height);
        int border = hasError ? ImGuiTheme.COL_DANGER
                : isActive ? ImGuiTheme.COL_FOCUS : isHovered ? ImGuiTheme.COL_BORDER_HOVER : ImGuiTheme.COL_BORDER;
        draw.addRect(x + 0.5f, y + 0.5f, x + width - 0.5f, y + height - 0.5f, border, m.radius());
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, height);
        return new Field(isCommitted, isActive);
    }

    /** An on/off switch at the cursor; {@code true} when clicked. */
    boolean toggle(String id, boolean isOn) {
        return ui.toggleRow(id, "", isOn, ui.toggleWidth());
    }

    /** The size of {@link #tickBox}. */
    float tickBoxSize() {
        return ui.fonts().body().getFontSize() * BOX_EM;
    }

    /** A tick box at the cursor; {@code true} when clicked while enabled. A disabled box is faded. */
    boolean tickBox(String id, boolean isTicked, boolean isEnabled) {
        float size = tickBoxSize();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImGui.beginDisabled(!isEnabled);
        boolean clicked = ImGui.invisibleButton(id, size, size);
        boolean isHovered = isEnabled && ImGui.isItemHovered();
        ImGui.endDisabled();
        paintTick(ImGui.getWindowDrawList(), x, y, size, isTicked, isHovered,
                isEnabled ? 1f : DISABLED_TICK_ALPHA);
        return clicked && isEnabled;
    }

    private static void paintTick(ImDrawList draw, float x, float y, float size, boolean isTicked,
                                  boolean isHovered, float alpha) {
        if (isTicked) {
            draw.addRectFilled(x, y, x + size, y + size, Controls.scaleAlpha(ImGuiTheme.COL_ACCENT, alpha),
                    BOX_RADIUS_PX);
            draw.pathClear();
            draw.pathLineTo(x + size * TICK_START_X, y + size * TICK_START_Y);
            draw.pathLineTo(x + size * TICK_MID_X, y + size * TICK_MID_Y);
            draw.pathLineTo(x + size * TICK_END_X, y + size * TICK_END_Y);
            draw.pathStroke(Controls.scaleAlpha(ImGuiTheme.COL_ON_ACCENT, alpha), 0, TICK_STROKE_PX);
            return;
        }
        int border = isHovered ? ImGuiTheme.COL_FG2 : ImGuiTheme.COL_FG3;
        draw.addRect(x + 0.5f, y + 0.5f, x + size - 0.5f, y + size - 0.5f, Controls.scaleAlpha(border, alpha),
                BOX_RADIUS_PX);
    }

    private static void pushBare(ImFont font, float height) {
        ImGui.pushFont(font);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 0f, (height - font.getFontSize()) * 0.5f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameBorderSize, 0f);
        Controls.pushColor(ImGuiCol.FrameBg, 0);
        Controls.pushColor(ImGuiCol.FrameBgHovered, 0);
        Controls.pushColor(ImGuiCol.FrameBgActive, 0);
        Controls.pushColor(ImGuiCol.TextDisabled, ImGuiTheme.COL_FG3);
        Controls.pushColor(ImGuiCol.Text, ImGuiTheme.COL_FG);
    }

    private static void popBare() {
        ImGui.popStyleColor(BARE_COLOURS);
        ImGui.popStyleVar(BARE_VARS);
        ImGui.popFont();
    }
}
