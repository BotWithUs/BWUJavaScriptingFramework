package com.botwithus.bot.cli.gui.pages.management;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.management.Target;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.List;
import java.util.Locale;

/**
 * The page's own small controls, drawn from the same tokens as the shared kit:
 * the text-only link button, the coloured icon button, the tab strip, a
 * target chip, the state label, section headings and the spinner. Interactive
 * ones lay themselves out at the cursor and advance it, like a stock widget;
 * the painters draw at the position they are given.
 */
final class ManagementWidgets {

    static final int COL_ROW_HOVER = Controls.scaleAlpha(ImGuiTheme.COL_FG, 0.03f);
    static final int COL_ROW_SELECTED = Controls.scaleAlpha(ImGuiTheme.COL_INFO, 0.08f);
    /** Glyphs the shared icon table does not carry. */
    static final String GLOBE = "\uF0AC";
    static final String ANGLE_UP = "\uF106";

    private static final float HOVER_SPEED = 1f / ImGuiTheme.DURATION_FAST_S;
    private static final float TAB_H_EM = 2.267f;
    private static final float TAB_BAR_PX = 2f;
    private static final float CHIP_H_EM = 1.467f;
    private static final float CHIP_PAD_EM = 0.467f;
    private static final float CHIP_GAP_EM = 0.333f;
    private static final float DOT_EM = 0.4f;
    private static final float SPIN_PERIOD_S = 0.9f;
    private static final float SPIN_SWEEP = (float) (Math.PI * 1.4);
    private static final float SPIN_STROKE_PX = 2f;
    private static final int SPIN_SEGMENTS = 16;
    private static final float DASH_EM = 0.267f;

    private final Controls ui;

    ManagementWidgets(Controls ui) {
        this.ui = ui;
    }

    Controls ui() {
        return ui;
    }

    ImGuiTheme.Metrics m() {
        return ui.m();
    }

    /** The body font size: the unit every ratio here multiplies. */
    float fs() {
        return ui.fonts().body().getFontSize();
    }

    // ── Buttons ────────────────────────────────────────────────────────────

    float linkWidth(String icon, String label) {
        float w = m().u(2) * 2f + ui.width(ui.fonts().smallMedium(), label);
        return icon == null ? w : w + ui.width(ui.fonts().caption(), icon) + m().u(1.5f);
    }

    /** A text-only button: secondary text that brightens on hover. Returns true when clicked. */
    boolean link(String id, String icon, String label, float height) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float w = linkWidth(icon, label);
        boolean clicked = ImGui.invisibleButton(id, w, height);
        float t = ui.motion().step("mlk:" + id, ImGui.isItemHovered() ? 1f : 0f, HOVER_SPEED);
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + w, y + height, Controls.scaleAlpha(ImGuiTheme.COL_ELEVATED, t), m().radius());
        int fg = Controls.lerp(ImGuiTheme.COL_FG2, ImGuiTheme.COL_FG, t);
        float cx = x + m().u(2);
        if (icon != null) {
            ui.textCentredY(draw, ui.fonts().caption(), cx, y, height, fg, icon);
            cx += ui.width(ui.fonts().caption(), icon) + m().u(1.5f);
        }
        ui.textCentredY(draw, ui.fonts().smallMedium(), cx, y, height, fg, label);
        return clicked;
    }

    /** A square icon button in {@code fg}, tinted with {@code hoverBg} on hover. */
    boolean iconButton(String id, String glyph, int fg, int hoverBg, String tooltip, boolean enabled) {
        float size = m().controlSmallHeight();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImGui.beginDisabled(!enabled);
        boolean clicked = ImGui.invisibleButton(id, size, size);
        ImGui.endDisabled();
        boolean hovered = ImGui.isItemHovered();
        if (hovered) {
            ImGui.setTooltip(tooltip);
        }
        float t = ui.motion().step("mib:" + id, hovered && enabled ? 1f : 0f, HOVER_SPEED);
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + size, y + size, Controls.scaleAlpha(hoverBg, t), m().radius());
        ImFont font = ui.fonts().caption();
        int col = enabled ? fg : Controls.scaleAlpha(fg, ImGuiTheme.DISABLED_ALPHA);
        ui.textCentredY(draw, font, x + (size - ui.width(font, glyph)) * 0.5f, y, size, col, glyph);
        return clicked && enabled;
    }

    // ── Tabs ───────────────────────────────────────────────────────────────

    float tabHeight() {
        return fs() * TAB_H_EM;
    }

    /**
     * A row of text tabs with an underline under the selected one, at the cursor.
     *
     * @return the tab clicked this frame, or {@code -1}
     */
    int tabs(String id, List<String> labels, int selected) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        int clicked = -1;
        for (int i = 0; i < labels.size(); i++) {
            float w = ui.width(ui.fonts().smallMedium(), labels.get(i));
            ImGui.setCursorScreenPos(x, y);
            if (tab(id + ":" + i, labels.get(i), i == selected, w)) {
                clicked = i;
            }
            x += w + m().u(4);
        }
        return clicked;
    }

    private boolean tab(String id, String label, boolean isOn, float w) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float h = tabHeight();
        boolean clicked = ImGui.invisibleButton(id, w, h);
        float t = ui.motion().step("mtb:" + id, ImGui.isItemHovered() ? 1f : 0f, HOVER_SPEED);
        ImDrawList draw = ImGui.getWindowDrawList();
        int fg = isOn ? ImGuiTheme.COL_FG : Controls.lerp(ImGuiTheme.COL_FG2, ImGuiTheme.COL_FG, t);
        ui.textCentredY(draw, ui.fonts().smallMedium(), x, y, h, fg, label);
        if (isOn) {
            draw.addRectFilled(x, y + h - TAB_BAR_PX, x + w, y + h, ImGuiTheme.COL_FG, 1f);
        }
        return clicked;
    }

    // ── Target chips ───────────────────────────────────────────────────────

    /** The glyph for a kind of target: a globe, a stack of layers, or one person. */
    static String iconOf(Target target) {
        return switch (target) {
            case Target.Host _ -> GLOBE;
            case Target.Group _ -> Icons.LAYER_GROUP;
            case Target.ClientScript _ -> Icons.USER;
        };
    }

    float chipHeight() {
        return fs() * CHIP_H_EM;
    }

    float chipGap() {
        return fs() * CHIP_GAP_EM;
    }

    float chipWidth(String icon, String label) {
        float w = fs() * CHIP_PAD_EM * 2f + ui.width(ui.fonts().caption(), label);
        return icon == null ? w : w + ui.width(ui.fonts().caption(), icon) + m().u(1.25f);
    }

    /**
     * An outlined chip, its top-left at (x, y): a glyph and a label, the
     * label clipped to {@code maxWidth}. Returns the width drawn.
     */
    float chip(ImDrawList draw, float x, float y, String icon, String label, int fg, float maxWidth) {
        ImFont font = ui.fonts().caption();
        float iconW = icon == null ? 0f : ui.width(font, icon) + m().u(1.25f);
        String shown = ui.ellipsize(font, label, Math.max(0f, maxWidth - iconW - fs() * CHIP_PAD_EM * 2f));
        float w = chipWidth(icon, shown);
        float h = chipHeight();
        draw.addRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER, m().radiusSmall());
        float cx = x + fs() * CHIP_PAD_EM;
        if (icon != null) {
            ui.textCentredY(draw, font, cx, y, h, ImGuiTheme.COL_FG2, icon);
            cx += iconW;
        }
        ui.textCentredY(draw, font, cx, y, h, fg, shown);
        return w;
    }

    /** A dashed chip in the faintest text: "Not applied". Returns its width. */
    float dashedChip(ImDrawList draw, float x, float y, String label) {
        float w = chipWidth(null, label);
        float h = chipHeight();
        dashedRect(draw, x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER);
        ui.textCentredY(draw, ui.fonts().caption(), x + fs() * CHIP_PAD_EM, y, h, ImGuiTheme.COL_FG3, label);
        return w;
    }

    /** A dashed rectangle outline. */
    void dashedRect(ImDrawList draw, float x0, float y0, float x1, float y1, int col) {
        float dash = fs() * DASH_EM;
        dashedLine(draw, x0, y0, x1, y0, dash, col);
        dashedLine(draw, x0, y1, x1, y1, dash, col);
        dashedLine(draw, x0, y0, x0, y1, dash, col);
        dashedLine(draw, x1, y0, x1, y1, dash, col);
    }

    private static void dashedLine(ImDrawList draw, float x0, float y0, float x1, float y1, float dash, int col) {
        float len = (float) Math.hypot(x1 - x0, y1 - y0);
        if (len <= 0f) {
            return;
        }
        float dx = (x1 - x0) / len;
        float dy = (y1 - y0) / len;
        for (float d = 0f; d < len; d += dash * 2f) {
            float e = Math.min(len, d + dash);
            draw.addLine(x0 + dx * d, y0 + dy * d, x0 + dx * e, y0 + dy * e, col);
        }
    }

    // ── State ──────────────────────────────────────────────────────────────

    /** The colour of a state: blue while running, red after a crash, grey when stopped. */
    static int stateColor(RunState state) {
        return switch (state) {
            case RunState.Running _ -> ImGuiTheme.COL_INFO;
            case RunState.Crashed _ -> ImGuiTheme.COL_DANGER;
            case RunState.Stopped _ -> ImGuiTheme.COL_FG2;
        };
    }

    /** The icon tile's colours for a state: blue while running, red after a crash, plain otherwise. */
    static int tileFg(RunState state) {
        return stateColor(state);
    }

    static int tileBg(RunState state) {
        return switch (state) {
            case RunState.Running _ -> ImGuiTheme.COL_INFO_SOFT;
            case RunState.Crashed _ -> ImGuiTheme.COL_DANGER_SOFT;
            case RunState.Stopped _ -> ImGuiTheme.COL_ELEVATED;
        };
    }

    float stateWidth(RunState state) {
        float w = dotSize() + m().u(1.5f) + ui.width(ui.fonts().captionMedium(), state.label());
        String detail = state.detail();
        return detail.isEmpty() ? w : w + m().u(1.5f) + ui.width(ui.fonts().monoCaption(), detail);
    }

    /** A dot, the state's word and, while running, the uptime; vertically centred in {@code h}. */
    void state(ImDrawList draw, float x, float y, float h, RunState state) {
        int col = stateColor(state);
        float r = dotSize() * 0.5f;
        draw.addCircleFilled(x + r, y + h * 0.5f, r, col);
        float tx = x + dotSize() + m().u(1.5f);
        ui.textCentredY(draw, ui.fonts().captionMedium(), tx, y, h, col, state.label());
        String detail = state.detail();
        if (!detail.isEmpty()) {
            tx += ui.width(ui.fonts().captionMedium(), state.label()) + m().u(1.5f);
            ui.textCentredY(draw, ui.fonts().monoCaption(), tx, y, h, ImGuiTheme.COL_FG2, detail);
        }
    }

    private float dotSize() {
        return fs() * DOT_EM;
    }

    // ── Painters ───────────────────────────────────────────────────────────

    /** An upper-case, spaced-out section heading, its top-left at (x, y). */
    void heading(ImDrawList draw, float x, float y, String text) {
        ui.text(draw, ui.fonts().captionMedium(), x, y, ImGuiTheme.COL_FG3, text.toUpperCase(Locale.ROOT));
    }

    /** A turning arc, the working-on-it signal. */
    void spinner(ImDrawList draw, float cx, float cy, float r) {
        float start = (float) (ImGui.getTime() / SPIN_PERIOD_S * Math.PI * 2.0);
        draw.addCircle(cx, cy, r, ImGuiTheme.COL_INFO_SOFT, SPIN_SEGMENTS, SPIN_STROKE_PX);
        draw.pathClear();
        draw.pathArcTo(cx, cy, r, start, start + SPIN_SWEEP, SPIN_SEGMENTS);
        draw.pathStroke(ImGuiTheme.COL_INFO, 0, SPIN_STROKE_PX);
    }

    /** Shows {@code text} as a tooltip while the mouse is over the rectangle. */
    void tooltipOver(float x, float y, float w, float h, String text) {
        if (ImGui.isMouseHoveringRect(x, y, x + w, y + h) && ImGui.isWindowHovered()) {
            ImGui.setTooltip(text);
        }
    }

    /** Lines of {@code text} wrapped to {@code width} in {@code font}, each {@code lineH} apart; returns the height. */
    float paragraph(ImDrawList draw, ImFont font, float x, float y, float width, float lineH, int col, String text) {
        float ly = y;
        for (String line : ui.wrap(font, text, width)) {
            ui.text(draw, font, x, ly + (lineH - font.getFontSize()) * 0.5f, col, line);
            ly += lineH;
        }
        return ly - y;
    }
}
