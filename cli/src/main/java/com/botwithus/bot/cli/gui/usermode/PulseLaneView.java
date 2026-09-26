package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Motion;

import imgui.ImDrawList;
import imgui.flag.ImDrawFlags;

/**
 * Draws the 24-bar pulse lane at the bottom of a client card: live bars when
 * running, a flat baseline when idle, a breathing row when loading, and a dashed
 * red flatline when the client is lost or its script crashed.
 */
final class PulseLaneView {

    /** Opacity of the older half of a running lane. */
    private static final float OLDER_ALPHA = 0.45f;
    /** Loading bars breathe between these opacities. */
    private static final float BREATHE_LOW = 0.35f;
    private static final float BREATHE_HIGH = 0.8f;
    private static final float BREATHE_HEIGHT = 0.3f;
    private static final float DEAD_ALPHA = 0.8f;
    private static final float DASH_EM = 0.2f;
    private static final float CAP_LIFT_EM = 0.267f;
    private static final float BAR_ROUNDING = 1f;

    private final Controls ui;

    PulseLaneView(Controls ui) {
        this.ui = ui;
    }

    void running(ImDrawList draw, float x, float y, float w, float h, long[] recentNanos, double avgMs) {
        float barW = barWidth(w);
        float gap = ui.m().barGap();
        for (PulseLane.Bar bar : PulseLane.bars(recentNanos, avgMs)) {
            float bx = x + bar.slot() * (barW + gap);
            float top = y + h - h * bar.height();
            int col = bar.spike() ? ImGuiTheme.COL_WARN : ImGuiTheme.COL_ACCENT;
            if (bar.older()) {
                col = Controls.scaleAlpha(col, OLDER_ALPHA);
            }
            draw.addRectFilled(bx, top, bx + barW, y + h, col, BAR_ROUNDING, ImDrawFlags.RoundCornersTop);
        }
        baseline(draw, x, y + h, w, ImGuiTheme.COL_BORDER);
    }

    void idle(ImDrawList draw, float x, float y, float w, float h) {
        baseline(draw, x, y + h, w, ImGuiTheme.COL_BORDER);
        caption(draw, x + w, y + h, "no loops");
    }

    void loading(ImDrawList draw, float x, float y, float w, float h) {
        float breathe = Motion.pulse(1.0 / ImGuiTheme.PULSE_PERIOD_S);
        int col = Controls.scaleAlpha(ImGuiTheme.COL_INFO,
                BREATHE_LOW + (BREATHE_HIGH - BREATHE_LOW) * breathe);
        float barW = barWidth(w);
        float gap = ui.m().barGap();
        float top = y + h - h * BREATHE_HEIGHT;
        for (int slot = 0; slot < PulseLane.SLOTS; slot++) {
            float bx = x + slot * (barW + gap);
            draw.addRectFilled(bx, top, bx + barW, y + h, col, BAR_ROUNDING, ImDrawFlags.RoundCornersTop);
        }
        baseline(draw, x, y + h, w, ImGuiTheme.COL_BORDER);
    }

    void dead(ImDrawList draw, float x, float y, float w, float h) {
        float dash = Math.max(ui.m().hairline() * 2f, ui.fonts().body().getFontSize() * DASH_EM);
        int col = Controls.scaleAlpha(ImGuiTheme.COL_DANGER, DEAD_ALPHA);
        float lineY = y + h - ui.m().hairline() * 0.5f;
        for (float dx = 0f; dx < w; dx += dash * 2f) {
            draw.addLine(x + dx, lineY, x + Math.min(w, dx + dash), lineY, col, ui.m().hairline());
        }
        caption(draw, x + w, y + h, "no signal");
    }

    private float barWidth(float w) {
        return (w - ui.m().barGap() * (PulseLane.SLOTS - 1)) / PulseLane.SLOTS;
    }

    private void baseline(ImDrawList draw, float x, float y, float w, int col) {
        float lineY = y - ui.m().hairline() * 0.5f;
        draw.addLine(x, lineY, x + w, lineY, col, ui.m().hairline());
    }

    /** Small grey caption sitting just above the right end of the baseline. */
    private void caption(ImDrawList draw, float right, float bottom, String text) {
        var font = ui.fonts().monoCaption();
        float lift = ui.fonts().body().getFontSize() * CAP_LIFT_EM;
        ui.text(draw, font, right - ui.width(font, text), bottom - lift - font.getFontSize(),
                ImGuiTheme.COL_FG3, text);
    }
}
