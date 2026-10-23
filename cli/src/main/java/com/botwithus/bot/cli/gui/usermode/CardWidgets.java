package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

/**
 * The small controls a client card draws that the shared kit does not have: a
 * square icon button in three tints, a switch with its label on the right, and
 * the working spinner.
 */
final class CardWidgets {

    /** How an icon button is tinted: outlined, or on a soft run or stop wash. */
    enum IconTint { PLAIN, RUN, STOP }

    /** The switch track, as the prototype's 28 × 16 px at a 15 px body. */
    private static final float SWITCH_W_EM = 1.867f;
    private static final float SWITCH_H_EM = 1.067f;
    private static final float KNOB_INSET_PX = 2f;
    private static final float SPINNER_PERIOD_S = 0.9f;
    private static final float SPINNER_SWEEP = (float) (Math.PI / 2);
    private static final float SPINNER_STROKE_PX = 2f;
    private static final float SPINNER_EM = 0.8f;
    private static final float HOVER_SPEED = 1f / ImGuiTheme.DURATION_FAST_S;

    private final Controls ui;

    CardWidgets(Controls ui) {
        this.ui = ui;
    }

    /**
     * A square button with a centred icon at the cursor, and a tooltip naming what
     * it does.
     *
     * @return whether it was clicked this frame
     */
    boolean iconButton(String id, String icon, IconTint tint, String tooltip, boolean enabled) {
        float size = ui.m().controlHeight();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImGui.beginDisabled(!enabled);
        boolean clicked = ImGui.invisibleButton(id, size, size);
        boolean hovered = ImGui.isItemHovered();
        ImGui.endDisabled();
        if (ImGui.isMouseHoveringRect(x, y, x + size, y + size) && ImGui.isWindowHovered()) {
            ImGui.setTooltip(tooltip);
        }
        float t = ui.motion().step("icon:" + id, enabled && hovered ? 1f : 0f, HOVER_SPEED);
        float alpha = enabled ? 1f : ImGuiTheme.DISABLED_ALPHA;
        paintIconButton(ImGui.getWindowDrawList(), x, y, size, icon, tint, t, alpha);
        return clicked && enabled;
    }

    private void paintIconButton(ImDrawList draw, float x, float y, float size, String icon, IconTint tint,
                                 float hoverT, float alpha) {
        float r = ui.m().radius();
        int bg = switch (tint) {
            case PLAIN -> Controls.scaleAlpha(ImGuiTheme.COL_ELEVATED, hoverT);
            case RUN -> Controls.lerp(ImGuiTheme.COL_ACCENT_SOFT, ImGuiTheme.COL_ACCENT_SOFT_HOVER, hoverT);
            case STOP -> Controls.lerp(ImGuiTheme.COL_DANGER_SOFT, ImGuiTheme.COL_DANGER_SOFT_HOVER, hoverT);
        };
        int fg = switch (tint) {
            case PLAIN -> Controls.lerp(ImGuiTheme.COL_FG2, ImGuiTheme.COL_FG, hoverT);
            case RUN -> ImGuiTheme.COL_ACCENT;
            case STOP -> ImGuiTheme.COL_DANGER;
        };
        draw.addRectFilled(x, y, x + size, y + size, Controls.scaleAlpha(bg, alpha), r);
        if (tint == IconTint.PLAIN) {
            int border = Controls.lerp(ImGuiTheme.COL_BORDER, ImGuiTheme.COL_BORDER_HOVER, hoverT);
            draw.addRect(x + 0.5f, y + 0.5f, x + size - 0.5f, y + size - 0.5f, Controls.scaleAlpha(border, alpha), r);
        }
        ImFont font = ui.fonts().caption();
        float iw = ui.width(font, icon);
        ui.textCentredY(draw, font, x + (size - iw) * 0.5f, y, size, Controls.scaleAlpha(fg, alpha), icon);
    }

    /** Width of {@link #labelledSwitch} with {@code label}. */
    float switchWidth(String label) {
        float fs = ui.fonts().body().getFontSize();
        return fs * SWITCH_W_EM + ui.m().u(2) + ui.width(ui.fonts().caption(), label);
    }

    /**
     * A switch followed by its label, at the cursor, {@code h} tall.
     *
     * @return whether it was clicked this frame, which flips it
     */
    boolean labelledSwitch(String id, String label, boolean isOn, float h, String tooltip) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float w = switchWidth(label);
        boolean clicked = ImGui.invisibleButton(id, w, h);
        boolean hovered = ImGui.isItemHovered();
        if (hovered) {
            ImGui.setTooltip(tooltip);
        }
        ImDrawList draw = ImGui.getWindowDrawList();
        float fs = ui.fonts().body().getFontSize();
        float tw = fs * SWITCH_W_EM;
        float th = fs * SWITCH_H_EM;
        float ty = y + (h - th) * 0.5f;
        float t = ui.motion().step("switch:" + id, isOn ? 1f : 0f, HOVER_SPEED);
        draw.addRectFilled(x, ty, x + tw, ty + th, Controls.lerp(ImGuiTheme.COL_ELEVATED, ImGuiTheme.COL_ACCENT, t),
                th * 0.5f);
        draw.addRect(x + 0.5f, ty + 0.5f, x + tw - 0.5f, ty + th - 0.5f,
                Controls.lerp(ImGuiTheme.COL_BORDER, ImGuiTheme.COL_ACCENT, t), th * 0.5f);
        float knobR = th * 0.5f - KNOB_INSET_PX - 1f;
        float kx0 = x + KNOB_INSET_PX + 1f + knobR;
        float kx = kx0 + (tw - (kx0 - x) * 2f) * t;
        draw.addCircleFilled(kx, ty + th * 0.5f, knobR, Controls.lerp(ImGuiTheme.COL_FG2, ImGuiTheme.COL_ON_ACCENT, t));
        int labelCol = hovered ? ImGuiTheme.COL_FG : ImGuiTheme.COL_FG2;
        ui.textCentredY(draw, ui.fonts().caption(), x + tw + ui.m().u(2), y, h, labelCol, label);
        return clicked;
    }

    /** The working spinner, centred on ({@code cx}, {@code cy}), in {@code tone}. */
    void spinner(ImDrawList draw, float cx, float cy, CardTone tone) {
        float r = ui.fonts().body().getFontSize() * SPINNER_EM * 0.5f - SPINNER_STROKE_PX * 0.5f;
        draw.addCircle(cx, cy, r, tone.bg(), 0, SPINNER_STROKE_PX);
        float a0 = (float) ((ImGui.getTime() % SPINNER_PERIOD_S) / SPINNER_PERIOD_S * Math.PI * 2);
        draw.pathClear();
        draw.pathArcTo(cx, cy, r, a0, a0 + SPINNER_SWEEP);
        draw.pathStroke(tone.fg(), 0, SPINNER_STROKE_PX);
    }
}
