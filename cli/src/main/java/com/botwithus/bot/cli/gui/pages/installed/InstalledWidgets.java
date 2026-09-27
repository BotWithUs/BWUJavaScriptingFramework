package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Motion;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.List;
import java.util.Locale;

/**
 * The page's own small controls, drawn from the same tokens as the shared kit:
 * the labelled switch, the text-only link button, the coloured icon button, the
 * tab strip, a client's dot, the source badge, pills, section headings and the
 * spinner. Interactive ones lay themselves out at the cursor and advance it,
 * like a stock widget; the painters draw at the position they are given.
 */
final class InstalledWidgets {

    static final int COL_ROW_HOVER = Controls.scaleAlpha(ImGuiTheme.COL_FG, 0.03f);
    static final int COL_ROW_SELECTED = Controls.scaleAlpha(ImGuiTheme.COL_INFO, 0.08f);
    /** Glyphs the shared icon table does not carry. */
    static final String ANGLE_UP = "\uF106";
    static final String ARROW_UP = "\uF062";

    private static final float HOVER_SPEED = 1f / ImGuiTheme.DURATION_FAST_S;
    private static final float SWITCH_W_EM = 1.867f;
    private static final float SWITCH_H_EM = 1.067f;
    private static final float KNOB_INSET_PX = 3f;
    private static final float DOT_EM = 0.467f;
    private static final float DOT_GAP_PX = 3f;
    private static final float TAB_H_EM = 2.267f;
    private static final float TAB_BAR_PX = 2f;
    private static final float PILL_PAD_EM = 0.4f;
    private static final float PILL_H_EM = 1.2f;
    private static final float BADGE_H_EM = 1.467f;
    private static final float SPIN_PERIOD_S = 0.9f;
    private static final float SPIN_SWEEP = (float) (Math.PI * 1.4);
    private static final float SPIN_STROKE_PX = 2f;
    private static final int SPIN_SEGMENTS = 16;

    private final Controls ui;

    InstalledWidgets(Controls ui) {
        this.ui = ui;
    }

    Controls ui() {
        return ui;
    }

    ImGuiTheme.Metrics m() {
        return ui.m();
    }

    // ── Switch ─────────────────────────────────────────────────────────────

    float switchWidth(String label) {
        return fs() * SWITCH_W_EM + m().u(2) + ui.width(ui.fonts().small(), label);
    }

    /** A switch followed by its label, {@code height} tall. Returns true when clicked. */
    boolean switchLabel(String id, String label, boolean isOn, float height, String tooltip) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        boolean clicked = ImGui.invisibleButton(id, switchWidth(label), height);
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(tooltip);
        }
        ImDrawList draw = ImGui.getWindowDrawList();
        float tw = fs() * SWITCH_W_EM;
        float th = fs() * SWITCH_H_EM;
        float ty = y + (height - th) * 0.5f;
        float t = Motion.step("isw:" + id, isOn ? 1f : 0f, HOVER_SPEED);
        draw.addRectFilled(x, ty, x + tw, ty + th, Controls.lerp(ImGuiTheme.COL_ELEVATED, ImGuiTheme.COL_ACCENT, t),
                th * 0.5f);
        draw.addRect(x + 0.5f, ty + 0.5f, x + tw - 0.5f, ty + th - 0.5f,
                Controls.lerp(ImGuiTheme.COL_BORDER, ImGuiTheme.COL_ACCENT, t), th * 0.5f);
        float r = th * 0.5f - KNOB_INSET_PX;
        float kx = x + KNOB_INSET_PX + r + (tw - (KNOB_INSET_PX + r) * 2f) * t;
        draw.addCircleFilled(kx, ty + th * 0.5f, r, Controls.lerp(ImGuiTheme.COL_FG2, ImGuiTheme.COL_ON_ACCENT, t));
        ui.textCentredY(draw, ui.fonts().small(), x + tw + m().u(2), y, height, ImGuiTheme.COL_FG, label);
        return clicked;
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
        float t = Motion.step("ilk:" + id, ImGui.isItemHovered() ? 1f : 0f, HOVER_SPEED);
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
    boolean iconButton(String id, String glyph, int fg, int hoverBg, String tooltip) {
        float size = m().controlSmallHeight();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        boolean clicked = ImGui.invisibleButton(id, size, size);
        boolean hovered = ImGui.isItemHovered();
        if (hovered) {
            ImGui.setTooltip(tooltip);
        }
        float t = Motion.step("iib:" + id, hovered ? 1f : 0f, HOVER_SPEED);
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + size, y + size, Controls.scaleAlpha(hoverBg, t), m().radius());
        ImFont font = ui.fonts().caption();
        ui.textCentredY(draw, font, x + (size - ui.width(font, glyph)) * 0.5f, y, size, fg, glyph);
        return clicked;
    }

    // ── Tabs ───────────────────────────────────────────────────────────────

    float tabHeight() {
        return fs() * TAB_H_EM;
    }

    /**
     * A row of text tabs with an underline under the selected one, at the cursor.
     *
     * @param counts shown after each label in mono, or {@code null} for none
     * @return the tab clicked this frame, or {@code -1}
     */
    int tabs(String id, List<String> labels, List<String> counts, int selected) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        int clicked = -1;
        for (int i = 0; i < labels.size(); i++) {
            String count = counts.get(i);
            float w = tabWidth(labels.get(i), count);
            ImGui.setCursorScreenPos(x, y);
            if (tab(id + ":" + i, labels.get(i), count, i == selected, w)) {
                clicked = i;
            }
            x += w + m().u(4);
        }
        return clicked;
    }

    private float tabWidth(String label, String count) {
        float w = ui.width(ui.fonts().smallMedium(), label);
        return count == null ? w : w + m().u(1.5f) + ui.width(ui.fonts().monoCaption(), count);
    }

    private boolean tab(String id, String label, String count, boolean isOn, float w) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float h = tabHeight();
        boolean clicked = ImGui.invisibleButton(id, w, h);
        float t = Motion.step("itb:" + id, ImGui.isItemHovered() ? 1f : 0f, HOVER_SPEED);
        ImDrawList draw = ImGui.getWindowDrawList();
        int fg = isOn ? ImGuiTheme.COL_FG : Controls.lerp(ImGuiTheme.COL_FG2, ImGuiTheme.COL_FG, t);
        ui.textCentredY(draw, ui.fonts().smallMedium(), x, y, h, fg, label);
        if (count != null) {
            float cx = x + ui.width(ui.fonts().smallMedium(), label) + m().u(1.5f);
            ui.textCentredY(draw, ui.fonts().monoCaption(), cx, y, h, ImGuiTheme.COL_FG2, count);
        }
        if (isOn) {
            draw.addRectFilled(x, y + h - TAB_BAR_PX, x + w, y + h, ImGuiTheme.COL_FG, 1f);
        }
        return clicked;
    }

    // ── Painters ───────────────────────────────────────────────────────────

    float dotSize() {
        return Math.max(DOT_GAP_PX * 2f, fs() * DOT_EM);
    }

    float dotStride() {
        return dotSize() + DOT_GAP_PX;
    }

    /** One client's dot, its top-left at (x, y): filled in its state's colour, hollow when not connected. */
    void dot(ImDrawList draw, float x, float y, RunnerState state) {
        float r = dotSize() * 0.5f;
        float cx = x + r;
        float cy = y + r;
        if (state == RunnerState.OFFLINE) {
            draw.addCircle(cx, cy, r - 0.5f, ImGuiTheme.COL_FG3, SPIN_SEGMENTS, m().hairline());
            return;
        }
        draw.addCircleFilled(cx, cy, r, stateColor(state));
    }

    static int stateColor(RunnerState state) {
        return switch (state) {
            case RUNNING -> ImGuiTheme.COL_ACCENT;
            case STALLED -> ImGuiTheme.COL_WARN;
            case CRASHED, CUT_OFF -> ImGuiTheme.COL_DANGER;
            case STOPPED, OFFLINE -> ImGuiTheme.COL_FG3;
        };
    }

    /** The status line colour in the Clients tab. */
    static int detailColor(RunnerState state) {
        return state == RunnerState.STOPPED || state == RunnerState.OFFLINE ? ImGuiTheme.COL_FG2 : stateColor(state);
    }

    float sourceBadgeWidth(ScriptSource source) {
        return m().u(1.75f) * 2f + ui.width(ui.fonts().caption(), source.icon()) + m().u(1.25f)
                + ui.width(ui.fonts().caption(), source.label());
    }

    /** The outlined Store / Local build badge, its top-left at (x, y); hover shows where it came from. */
    void sourceBadge(ImDrawList draw, float x, float y, ScriptSource source) {
        float w = sourceBadgeWidth(source);
        float h = fs() * BADGE_H_EM;
        draw.addRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER, m().radiusSmall());
        float cx = x + m().u(1.75f);
        ui.textCentredY(draw, ui.fonts().caption(), cx, y, h, ImGuiTheme.COL_FG2, source.icon());
        cx += ui.width(ui.fonts().caption(), source.icon()) + m().u(1.25f);
        ui.textCentredY(draw, ui.fonts().caption(), cx, y, h, ImGuiTheme.COL_FG, source.label());
        tooltipOver(x, y, w, h, source.hint());
    }

    float badgeHeight() {
        return fs() * BADGE_H_EM;
    }

    float pillWidth(String text) {
        return fs() * PILL_PAD_EM * 2f + ui.width(ui.fonts().captionMedium(), text);
    }

    /** A small rounded pill of {@code text}, vertically centred on {@code cy}. */
    void pill(ImDrawList draw, float x, float cy, String text, int fg, int bg) {
        float h = fs() * PILL_H_EM;
        float w = pillWidth(text);
        draw.addRectFilled(x, cy - h * 0.5f, x + w, cy + h * 0.5f, bg, h * 0.5f);
        ui.textCentredY(draw, ui.fonts().captionMedium(), x + fs() * PILL_PAD_EM, cy - h * 0.5f, h, fg, text);
    }

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

    /** The body font size: the unit every ratio here multiplies. */
    float fs() {
        return ui.fonts().body().getFontSize();
    }
}
