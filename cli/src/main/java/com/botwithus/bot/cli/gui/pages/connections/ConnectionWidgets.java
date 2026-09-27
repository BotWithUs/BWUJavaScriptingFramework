package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Motion;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImString;

import java.util.Locale;

/**
 * The Connections page's own small controls, drawn from the shared tokens: the
 * switch, the console-target radio, spinners, the link-state label, icon and
 * text buttons, section titles, key/value lines and link chips. Each lays itself
 * out at the cursor and advances it, like a stock widget, unless it says it
 * paints at a given point instead.
 */
final class ConnectionWidgets {

    static final int COL_ROW_HOVER = Controls.scaleAlpha(ImGuiTheme.COL_FG, 0.03f);
    static final int COL_ROW_SELECTED = Controls.scaleAlpha(ImGuiTheme.COL_INFO, 0.08f);
    /** Closed rows are drawn at this alpha: remembered, not live. */
    static final float DIM_ALPHA = 0.6f;
    static final float LINE_HEIGHT = 1.45f;

    private static final float HOVER_SPEED = 1f / ImGuiTheme.DURATION_FAST_S;
    private static final float SWITCH_W_EM = 1.867f;
    private static final float SWITCH_H_EM = 1.067f;
    private static final float SWITCH_KNOB_INSET_PX = 2f;
    private static final float RADIO_EM = 1.2f;
    private static final float RADIO_STROKE_PX = 1.5f;
    private static final float RADIO_DOT_OF_SIZE = 0.22f;
    private static final float RADIO_DISABLED_ALPHA = 0.3f;
    private static final float SPIN_PERIOD_S = 0.9f;
    private static final float SPIN_SWEEP = (float) (Math.PI * 1.4);
    private static final float SPIN_STROKE_PX = 2f;
    private static final float SPIN_TRACK_ALPHA = 0.25f;
    private static final float CHIP_H_EM = 1.6f;

    private final Controls ui;

    ConnectionWidgets(Controls ui) {
        this.ui = ui;
    }

    Controls ui() {
        return ui;
    }

    /** The colour a {@link Tone} is drawn in. */
    static int colour(Tone tone) {
        return switch (tone) {
            case OK -> ImGuiTheme.COL_ACCENT;
            case WARN -> ImGuiTheme.COL_WARN;
            case ERROR -> ImGuiTheme.COL_DANGER;
            case NEUTRAL -> ImGuiTheme.COL_FG3;
        };
    }

    // ── Switch and radio ───────────────────────────────────────────────────

    float switchWidth() {
        return ui.fonts().body().getFontSize() * SWITCH_W_EM;
    }

    /** A bare on/off switch, vertically centred in {@code rowH}. Returns true when clicked while enabled. */
    boolean switchToggle(String id, boolean isOn, boolean enabled, float rowH) {
        float fs = ui.fonts().body().getFontSize();
        float w = fs * SWITCH_W_EM;
        float h = fs * SWITCH_H_EM;
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY() + (rowH - h) * 0.5f;
        ImGui.setCursorScreenPos(x, y);
        ImGui.beginDisabled(!enabled);
        boolean clicked = ImGui.invisibleButton(id, w, h);
        ImGui.endDisabled();
        float t = Motion.step("sw:" + id, isOn ? 1f : 0f, HOVER_SPEED);
        float alpha = enabled ? 1f : ImGuiTheme.DISABLED_ALPHA;
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + w, y + h,
                Controls.scaleAlpha(Controls.lerp(ImGuiTheme.COL_ELEVATED, ImGuiTheme.COL_ACCENT, t), alpha), h * 0.5f);
        draw.addRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f,
                Controls.scaleAlpha(Controls.lerp(ImGuiTheme.COL_BORDER, ImGuiTheme.COL_ACCENT, t), alpha), h * 0.5f);
        float knobR = h * 0.5f - SWITCH_KNOB_INSET_PX - 1f;
        float kx0 = x + SWITCH_KNOB_INSET_PX + 1f + knobR;
        float kx = kx0 + (w - (kx0 - x) * 2f) * t;
        draw.addCircleFilled(kx, y + h * 0.5f, knobR,
                Controls.scaleAlpha(Controls.lerp(ImGuiTheme.COL_FG2, ImGuiTheme.COL_ON_ACCENT, t), alpha));
        return clicked && enabled;
    }

    float radioSize() {
        return ui.fonts().body().getFontSize() * RADIO_EM;
    }

    /** The console-target radio. Returns true when clicked while enabled. */
    boolean radio(String id, boolean isOn, boolean enabled) {
        float size = radioSize();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImGui.beginDisabled(!enabled);
        boolean clicked = ImGui.invisibleButton(id, size, size);
        boolean hovered = enabled && ImGui.isItemHovered();
        ImGui.endDisabled();
        float alpha = enabled ? 1f : RADIO_DISABLED_ALPHA;
        radioShape(ImGui.getWindowDrawList(), x + size * 0.5f, y + size * 0.5f, size * 0.5f, isOn, hovered, alpha);
        if (hovered) {
            ImGui.setTooltip(isOn ? "Console target: commands run here" : "Make this the console target");
        }
        return clicked && enabled;
    }

    /** A radio circle centred on (cx, cy), for the rows and the legend. */
    void radioShape(ImDrawList draw, float cx, float cy, float radius, boolean isOn, boolean hovered, float alpha) {
        int ring = isOn ? ImGuiTheme.COL_INFO : hovered ? ImGuiTheme.COL_FG : ImGuiTheme.COL_FG3;
        draw.addCircle(cx, cy, radius - RADIO_STROKE_PX * 0.5f, Controls.scaleAlpha(ring, alpha), 0, RADIO_STROKE_PX);
        if (isOn) {
            draw.addCircleFilled(cx, cy, radius * 2f * RADIO_DOT_OF_SIZE, Controls.scaleAlpha(ImGuiTheme.COL_INFO, alpha));
        }
    }

    // ── Spinner and link state ─────────────────────────────────────────────

    /** A turning arc centred on (cx, cy): something is being worked on. */
    void spinner(ImDrawList draw, float cx, float cy, float radius, int col) {
        float a0 = (float) ((ImGui.getTime() % SPIN_PERIOD_S) / SPIN_PERIOD_S * Math.PI * 2);
        draw.addCircle(cx, cy, radius, Controls.scaleAlpha(col, SPIN_TRACK_ALPHA), 0, SPIN_STROKE_PX);
        draw.pathClear();
        draw.pathArcTo(cx, cy, radius, a0, a0 + SPIN_SWEEP);
        draw.pathStroke(col, 0, SPIN_STROKE_PX);
    }

    /** The Link column's mark and words, painted at (x, y) centred in {@code h}. Returns the width used. */
    float linkState(ImDrawList draw, float x, float y, float h, LinkState state) {
        ImGuiTheme.Metrics m = ui.m();
        int col = linkColour(state);
        float cy = y + h * 0.5f;
        float mark = m.u(3);
        switch (state) {
            case LinkState.Identifying _, LinkState.Resuming _ -> spinner(draw, x + mark * 0.5f, cy, mark * 0.4f, col);
            case LinkState.NotResponding n when n.isRetrying() -> spinner(draw, x + mark * 0.5f, cy, mark * 0.4f, col);
            case LinkState.Found _ -> draw.addCircle(x + mark * 0.5f, cy, m.dot() * 0.5f, col);
            case LinkState.Closed _ -> ui.textCentredY(draw, ui.fonts().caption(), x, y, h, col, Icons.POWER);
            case LinkState.Connected _, LinkState.NotResponding _ ->
                    draw.addCircleFilled(x + mark * 0.5f, cy, m.dot() * 0.5f, col);
        }
        String label = ConnectionText.link(state);
        ui.textCentredY(draw, ui.fonts().captionMedium(), x + mark + m.u(1.5f), y, h, col, label);
        return mark + m.u(1.5f) + ui.width(ui.fonts().captionMedium(), label);
    }

    static int linkColour(LinkState state) {
        return switch (state) {
            case LinkState.Connected _ -> ImGuiTheme.COL_ACCENT;
            case LinkState.Identifying _, LinkState.Resuming _ -> ImGuiTheme.COL_INFO;
            case LinkState.NotResponding _ -> ImGuiTheme.COL_WARN;
            case LinkState.Found _, LinkState.Closed _ -> ImGuiTheme.COL_FG2;
        };
    }

    // ── Buttons ────────────────────────────────────────────────────────────

    float iconButtonSize() {
        return ui.m().controlSmallHeight();
    }

    /**
     * A square icon button. {@code isOn} tints it in the info colour, as the
     * output filter is while it applies; {@code isDanger} turns it red on hover.
     */
    boolean iconButton(String id, String icon, boolean isOn, boolean isDanger, String tooltip) {
        float size = iconButtonSize();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        boolean clicked = ImGui.invisibleButton(id, size, size);
        boolean hovered = ImGui.isItemHovered();
        float t = Motion.step("ib:" + id, hovered ? 1f : 0f, HOVER_SPEED);
        ImDrawList draw = ImGui.getWindowDrawList();
        int bg = isOn ? ImGuiTheme.COL_INFO_SOFT
                : Controls.scaleAlpha(isDanger ? ImGuiTheme.COL_DANGER_SOFT : ImGuiTheme.COL_ELEVATED, t);
        draw.addRectFilled(x, y, x + size, y + size, bg, ui.m().radius());
        int fg = isOn ? ImGuiTheme.COL_INFO
                : Controls.lerp(ImGuiTheme.COL_FG2, isDanger ? ImGuiTheme.COL_DANGER : ImGuiTheme.COL_FG, t);
        ImFont font = ui.fonts().caption();
        ui.textCentredY(draw, font, x + (size - ui.width(font, icon)) * 0.5f, y, size, fg, icon);
        if (hovered) {
            ImGui.setTooltip(tooltip);
        }
        return clicked;
    }

    float textButtonWidth(String label) {
        return ui.m().u(2) * 2f + ui.width(ui.fonts().captionMedium(), label);
    }

    /** A borderless grey text button, like Forget and Show all. Returns true when clicked. */
    boolean textButton(String id, String label, float h) {
        float w = textButtonWidth(label);
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        boolean clicked = ImGui.invisibleButton(id, w, h);
        float t = Motion.step("tb:" + id, ImGui.isItemHovered() ? 1f : 0f, HOVER_SPEED);
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + w, y + h, Controls.scaleAlpha(ImGuiTheme.COL_ELEVATED, t), ui.m().radius());
        ui.textCentredY(draw, ui.fonts().captionMedium(), x + ui.m().u(2), y, h,
                Controls.lerp(ImGuiTheme.COL_FG2, ImGuiTheme.COL_FG, t), label);
        return clicked;
    }

    // ── Text blocks ────────────────────────────────────────────────────────

    /** A small upper-case section title at (x, y). Returns the y under it. */
    float sectionTitle(ImDrawList draw, float x, float y, String title) {
        ImFont font = ui.fonts().captionMedium();
        ui.text(draw, font, x, y, ImGuiTheme.COL_FG3, title.toUpperCase(Locale.ROOT));
        return y + font.getFontSize() + ui.m().u(3);
    }

    float lineHeight(ImFont font) {
        return font.getFontSize() * LINE_HEIGHT;
    }

    /** A label on the left and a mono value on the right, across {@code w}. Returns the y under it. */
    float keyValue(ImDrawList draw, float x, float y, float w, String key, String value, int valueCol) {
        ImFont label = ui.fonts().caption();
        ImFont mono = ui.fonts().monoCaption();
        float h = lineHeight(label);
        ui.textCentredY(draw, label, x, y, h, ImGuiTheme.COL_FG2, key);
        float keyW = ui.width(label, key) + ui.m().u(4);
        String shown = ui.ellipsize(mono, value, w - keyW);
        ui.textCentredY(draw, mono, x + w - ui.width(mono, shown), y, h, valueCol, shown);
        return y + h + ui.m().u(1.5f);
    }

    /** A paragraph wrapped to {@code w} at (x, y). Returns the y under it. */
    float note(ImDrawList draw, float x, float y, float w, String text) {
        ImFont font = ui.fonts().caption();
        float cy = y;
        for (String line : ui.wrap(font, text, w)) {
            ui.text(draw, font, x, cy, ImGuiTheme.COL_FG2, line);
            cy += lineHeight(font);
        }
        return cy;
    }

    // ── Chips ──────────────────────────────────────────────────────────────

    float chipHeight() {
        return ui.fonts().body().getFontSize() * CHIP_H_EM;
    }

    float chipWidth(String label) {
        ImGuiTheme.Metrics m = ui.m();
        return m.u(2) * 2f + m.u(3) + m.u(1.5f) + ui.width(ui.fonts().caption(), label);
    }

    /**
     * A bordered link chip at the cursor: a status dot (or {@code icon}, when not
     * null) and a label. Returns true when clicked.
     */
    boolean chip(String id, String icon, int markCol, String label, String tooltip) {
        ImGuiTheme.Metrics m = ui.m();
        float w = chipWidth(label);
        float h = chipHeight();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        boolean clicked = ImGui.invisibleButton(id, w, h);
        boolean hovered = ImGui.isItemHovered();
        float t = Motion.step("chip:" + id, hovered ? 1f : 0f, HOVER_SPEED);
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + w, y + h, Controls.scaleAlpha(ImGuiTheme.COL_ELEVATED, t), m.radiusSmall());
        draw.addRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f,
                Controls.lerp(ImGuiTheme.COL_BORDER, ImGuiTheme.COL_BORDER_HOVER, t), m.radiusSmall());
        float mx = x + m.u(2);
        if (icon != null) {
            ImFont iconFont = ui.fonts().caption();
            ui.textCentredY(draw, iconFont, mx, y, h, markCol, icon);
        } else {
            draw.addCircleFilled(mx + m.u(3) * 0.5f, y + h * 0.5f, m.dot() * 0.5f, markCol);
        }
        ui.textCentredY(draw, ui.fonts().caption(), mx + m.u(3) + m.u(1.5f), y, h, ImGuiTheme.COL_FG, label);
        if (hovered) {
            ImGui.setTooltip(tooltip);
        }
        return clicked;
    }

    // ── Inputs ─────────────────────────────────────────────────────────────

    /**
     * A borderless text input at (x, y), for fields that draw their own frame.
     * Returns true once the user finishes an edit: Enter, Tab or clicking away.
     */
    boolean bareInput(String id, ImString buffer, ImFont font, float x, float y, float w, float h) {
        ImGui.setCursorScreenPos(x, y);
        ImGui.pushFont(font);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 0f, (h - font.getFontSize()) * 0.5f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameBorderSize, 0f);
        Controls.pushColor(ImGuiCol.FrameBg, 0);
        Controls.pushColor(ImGuiCol.FrameBgHovered, 0);
        Controls.pushColor(ImGuiCol.FrameBgActive, 0);
        Controls.pushColor(ImGuiCol.Text, ImGuiTheme.COL_FG);
        ImGui.setNextItemWidth(w);
        ImGui.inputText(id, buffer);
        boolean isDone = ImGui.isItemDeactivatedAfterEdit();
        ImGui.popStyleColor(4);
        ImGui.popStyleVar(2);
        ImGui.popFont();
        return isDone;
    }
}
