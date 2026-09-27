package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImDrawFlags;

/**
 * The Store's own small controls, drawn from the same tokens as the shared kit:
 * the favourite star, the tick box, the text-only link button, the filter check
 * chip, price badges, a spinner and a dashed outline. Each lays itself out at the
 * cursor and advances it, like a stock widget, unless it says otherwise.
 */
final class StoreWidgets {

    /** The favourite star reuses the amber hue: its one use that is not a status. */
    static final int COL_STAR = ImGuiTheme.COL_WARN;
    static final int COL_ROW_HOVER = Controls.scaleAlpha(ImGuiTheme.COL_FG, 0.03f);
    static final int COL_ROW_SELECTED = Controls.scaleAlpha(ImGuiTheme.COL_INFO, 0.08f);

    private static final float HOVER_SPEED = 1f / ImGuiTheme.DURATION_FAST_S;
    private static final int STAR_POINTS = 5;
    private static final float STAR_INNER_RATIO = 0.48f;
    private static final float STAR_RADIUS_OF_BOX = 0.3f;
    private static final float STAR_STROKE_PX = 1.3f;
    private static final float STAR_TOP = (float) (-Math.PI / 2);
    private static final float CHIP_BOX_EM = 0.933f;
    /** A category chip's box ticks light on dark, so it reads against the chip's raised fill. */
    private static final Controls.TickColors CHIP_TICK =
            new Controls.TickColors(ImGuiTheme.COL_FG, ImGuiTheme.COL_BG);
    private static final float SPIN_PERIOD_S = 0.9f;
    private static final float SPIN_SWEEP = (float) (Math.PI * 1.4);
    private static final float SPIN_STROKE_PX = 2f;
    private static final float DASH_PX = 4f;
    private static final float GAP_PX = 3f;
    private static final float BADGE_H_EM = 1.333f;
    private static final float BADGE_PAD_EM = 0.467f;
    /** A star drawn inline with text: its radius as a share of the text size. */
    static final float GLYPH_STAR_EM = 0.4f;
    private static final float LINE_HEIGHT = 1.45f;

    private final Controls ui;

    StoreWidgets(Controls ui) {
        this.ui = ui;
    }

    Controls ui() {
        return ui;
    }

    // ── Star ───────────────────────────────────────────────────────────────

    /** Width and height of the star button: {@code --h-control-sm}. */
    float starSize() {
        return ui.m().controlSmallHeight();
    }

    /** The favourite toggle: a filled amber star when on, an outline when off. Returns true when clicked. */
    boolean star(String id, boolean isOn) {
        float size = starSize();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        boolean clicked = ImGui.invisibleButton(id, size, size);
        float t = ui.motion().step("star:" + id, ImGui.isItemHovered() ? 1f : 0f, HOVER_SPEED);
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + size, y + size, Controls.scaleAlpha(ImGuiTheme.COL_ELEVATED, t),
                ui.m().radiusSmall());
        int col = isOn ? COL_STAR : Controls.lerp(ImGuiTheme.COL_FG3, ImGuiTheme.COL_FG, t);
        starShape(draw, x + size * 0.5f, y + size * 0.5f, size * STAR_RADIUS_OF_BOX, col, isOn);
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(isOn ? "Unstar" : "Star as a favourite");
        }
        return clicked;
    }

    /** A five-point star centred on (cx, cy), filled or outlined. */
    void starShape(ImDrawList draw, float cx, float cy, float radius, int col, boolean filled) {
        draw.pathClear();
        for (int i = 0; i < STAR_POINTS * 2; i++) {
            float r = i % 2 == 0 ? radius : radius * STAR_INNER_RATIO;
            float a = STAR_TOP + (float) (Math.PI * i / STAR_POINTS);
            draw.pathLineTo(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r);
        }
        if (filled) {
            draw.pathFillConcave(col);
        } else {
            draw.pathStroke(col, ImDrawFlags.Closed, STAR_STROKE_PX);
        }
    }

    // ── Buttons ────────────────────────────────────────────────────────────

    float linkWidth(String icon, String label) {
        ImGuiTheme.Metrics m = ui.m();
        float w = m.u(2) * 2f + ui.width(ui.fonts().smallMedium(), label);
        return icon == null ? w : w + ui.width(ui.fonts().caption(), icon) + m.u(1.5f);
    }

    /** A text-only button, grey until hovered ({@code .btn.link}). */
    boolean link(String id, String icon, String label, boolean enabled, float height) {
        ImGuiTheme.Metrics m = ui.m();
        float w = linkWidth(icon, label);
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImGui.beginDisabled(!enabled);
        boolean clicked = ImGui.invisibleButton(id, w, height);
        boolean hovered = enabled && ImGui.isItemHovered();
        ImGui.endDisabled();
        float t = ui.motion().step("link:" + id, hovered ? 1f : 0f, HOVER_SPEED);
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + w, y + height, Controls.scaleAlpha(ImGuiTheme.COL_ELEVATED, t), m.radius());
        int fg = Controls.lerp(ImGuiTheme.COL_FG2, ImGuiTheme.COL_FG, t);
        fg = enabled ? fg : Controls.scaleAlpha(fg, ImGuiTheme.DISABLED_ALPHA);
        float cx = x + m.u(2);
        if (icon != null) {
            ui.textCentredY(draw, ui.fonts().caption(), cx, y, height, fg, icon);
            cx += ui.width(ui.fonts().caption(), icon) + m.u(1.5f);
        }
        ui.textCentredY(draw, ui.fonts().smallMedium(), cx, y, height, fg, label);
        return clicked && enabled;
    }

    /** Link text inside a sentence: the text colour, underlined while hovered. Returns true when clicked. */
    boolean inlineLink(String id, String label, ImFont font) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float wide = ui.width(font, label);
        float h = font.getFontSize();
        boolean clicked = ImGui.invisibleButton(id, wide, h);
        ImDrawList draw = ImGui.getWindowDrawList();
        ui.text(draw, font, x, y, ImGuiTheme.COL_FG, label);
        if (ImGui.isItemHovered()) {
            draw.addLine(x, y + h, x + wide, y + h, ImGuiTheme.COL_FG, ui.m().hairline());
        }
        return clicked;
    }

    float checkChipWidth(String label) {
        ImGuiTheme.Metrics m = ui.m();
        return m.u(3) * 2f + chipBox() + m.u(1.5f) + ui.width(ui.fonts().small(), label);
    }

    private float chipBox() {
        return ui.fonts().body().getFontSize() * CHIP_BOX_EM;
    }

    /** A bordered filter toggle with a tick box and a label ({@code .chk}). Returns true when clicked. */
    boolean checkChip(String id, String label, boolean isOn) {
        ImGuiTheme.Metrics m = ui.m();
        float h = m.controlHeight();
        float w = checkChipWidth(label);
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        boolean clicked = ImGui.invisibleButton(id, w, h);
        float t = ui.motion().step("chk:" + id, ImGui.isItemHovered() ? 1f : 0f, HOVER_SPEED);
        ImDrawList draw = ImGui.getWindowDrawList();
        if (isOn) {
            draw.addRectFilled(x, y, x + w, y + h, ImGuiTheme.COL_ELEVATED, m.radius());
        }
        draw.addRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f,
                Controls.lerp(ImGuiTheme.COL_BORDER, ImGuiTheme.COL_BORDER_HOVER, t), m.radius());
        float box = chipBox();
        Controls.paintTickBox(draw, x + m.u(3), y + (h - box) * 0.5f, box, isOn, t > 0.5f, 1f, CHIP_TICK);
        int fg = isOn ? ImGuiTheme.COL_FG : Controls.lerp(ImGuiTheme.COL_FG2, ImGuiTheme.COL_FG, t);
        ui.textCentredY(draw, ui.fonts().small(), x + m.u(3) + box + m.u(1.5f), y, h, fg, label);
        return clicked;
    }

    // ── Section titles ─────────────────────────────────────────────────────

    /**
     * A section title, with the amber star before it when {@code starred}, and a mono
     * count after it. Returns the y under it.
     */
    float sectionHeader(ImDrawList draw, float x, float y, String title, String count, boolean starred) {
        ImFont font = ui.fonts().smallMedium();
        float h = font.getFontSize() * LINE_HEIGHT;
        float tx = x;
        if (starred) {
            float r = font.getFontSize() * GLYPH_STAR_EM;
            starShape(draw, x + r, y + h * 0.5f, r, StoreWidgets.COL_STAR, true);
            tx += r * 2f + ui.m().u(2);
        }
        ui.textCentredY(draw, font, tx, y, h, ImGuiTheme.COL_FG, title);
        tx += ui.width(font, title) + ui.m().u(2);
        ui.textCentredY(draw, ui.fonts().monoCaption(), tx, y, h, ImGuiTheme.COL_FG2, count);
        return y + h;
    }

    // ── Badges, spinner, outlines ──────────────────────────────────────────

    float badgeHeight() {
        return ui.fonts().body().getFontSize() * BADGE_H_EM;
    }

    float badgeWidth(String label) {
        return ui.fonts().body().getFontSize() * BADGE_PAD_EM * 2f + ui.width(ui.fonts().captionMedium(), label);
    }

    /** A small bordered label with its top-left at (x, y); {@code bg} 0 for none. Returns its width. */
    float badge(ImDrawList draw, float x, float y, String label, int fg, int bg, int border) {
        float w = badgeWidth(label);
        float h = badgeHeight();
        float r = ui.m().radiusSmall();
        if (bg != 0) {
            draw.addRectFilled(x, y, x + w, y + h, bg, r);
        }
        if (border != 0) {
            draw.addRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f, border, r);
        }
        ImFont font = ui.fonts().captionMedium();
        ui.textCentredY(draw, font, x + ui.fonts().body().getFontSize() * BADGE_PAD_EM, y, h, fg, label);
        return w;
    }

    /** A turning arc, the Store's "working on it" mark, centred on (cx, cy). */
    void spinner(ImDrawList draw, float cx, float cy, float radius, int col) {
        float a0 = (float) ((ImGui.getTime() % SPIN_PERIOD_S) / SPIN_PERIOD_S * Math.PI * 2);
        draw.addCircle(cx, cy, radius, Controls.scaleAlpha(col, ImGuiTheme.DISABLED_ALPHA * 0.5f), 0,
                SPIN_STROKE_PX);
        draw.pathClear();
        draw.pathArcTo(cx, cy, radius, a0, a0 + SPIN_SWEEP);
        draw.pathStroke(col, 0, SPIN_STROKE_PX);
    }

    /** A dashed rectangle outline; the corners are square. */
    void dashedRect(ImDrawList draw, float x0, float y0, float x1, float y1, int col) {
        dashes(draw, x0, y0, x1, y0, col);
        dashes(draw, x0, y1, x1, y1, col);
        dashes(draw, x0, y0, x0, y1, col);
        dashes(draw, x1, y0, x1, y1, col);
    }

    private static void dashes(ImDrawList draw, float x0, float y0, float x1, float y1, int col) {
        float len = (float) Math.hypot(x1 - x0, y1 - y0);
        float dx = (x1 - x0) / len;
        float dy = (y1 - y0) / len;
        for (float d = 0f; d < len; d += DASH_PX + GAP_PX) {
            float e = Math.min(len, d + DASH_PX);
            draw.addLine(x0 + dx * d, y0 + dy * d, x0 + dx * e, y0 + dy * e, col);
        }
    }

}
