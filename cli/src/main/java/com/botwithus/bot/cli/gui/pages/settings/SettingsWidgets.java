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
 * leaving the box after typing) rather than on every keystroke. The rest of the
 * page's controls come from {@link Controls}.
 */
final class SettingsWidgets {

    /** What a box did this frame. */
    record Field(boolean isCommitted, boolean isActive) {}

    private static final int BARE_COLOURS = 4;
    private static final int BARE_VARS = 2;

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
        boolean isEnter = ImGui.inputText("##" + id, buffer, ImGuiInputTextFlags.EnterReturnsTrue);
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

    private static void pushBare(ImFont font, float height) {
        ImGui.pushFont(font);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 0f, (height - font.getFontSize()) * 0.5f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameBorderSize, 0f);
        Controls.pushColor(ImGuiCol.FrameBg, 0);
        Controls.pushColor(ImGuiCol.FrameBgHovered, 0);
        Controls.pushColor(ImGuiCol.FrameBgActive, 0);
        Controls.pushColor(ImGuiCol.Text, ImGuiTheme.COL_FG);
    }

    private static void popBare() {
        ImGui.popStyleColor(BARE_COLOURS);
        ImGui.popStyleVar(BARE_VARS);
        ImGui.popFont();
    }
}
