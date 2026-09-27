package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.ImGuiTheme;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

/**
 * What the page shows with nothing in the table: a radar that sweeps while a
 * scan runs, and a line on what to check when no game client answered.
 */
final class ConnectionsEmpty {

    private static final float RADAR_EM = 4.8f;
    private static final float RADAR_INNER_INSET = 0.1667f;
    private static final float SWEEP_PERIOD_S = 1.6f;
    private static final float SWEEP_ARC = (float) (Math.PI * 0.5);
    private static final float SWEEP_STROKE_PX = 2f;
    private static final float TEXT_MAX_EM = 26.667f;
    private static final float ICON_SCALE = 1.2f;

    private final ConnectionWidgets w;
    private final Controls ui;

    ConnectionsEmpty(ConnectionWidgets widgets) {
        this.w = widgets;
        this.ui = widgets.ui();
    }

    /** Draws the state centred in the box from (x, top), {@code width} by {@code height}. */
    void render(ConnectionsView view, float x, float top, float width, float height) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float radar = ui.fonts().body().getFontSize() * RADAR_EM;
        ImFont heading = ui.fonts().bodyMedium();
        ImFont body = ui.fonts().small();
        float textW = Math.min(width - m.u(6) * 2f, ui.fonts().body().getFontSize() * TEXT_MAX_EM);
        String title = view.isScanning() ? "Looking for game clients…" : "No BotWithUs pipes found";
        String text = paragraph(view);
        int lines = ui.wrap(body, text, textW).size();
        float blockH = radar + m.u(4) + w.lineHeight(heading) + m.u(2) + lines * w.lineHeight(body) + m.u(3)
                + m.controlSmallHeight();
        float cx = x + width * 0.5f;
        float y = top + Math.max(m.u(6), (height - blockH) * 0.5f);
        radar(draw, cx, y + radar * 0.5f, radar * 0.5f, view.isScanning());
        y += radar + m.u(4);
        ui.text(draw, heading, cx - ui.width(heading, title) * 0.5f, y, ImGuiTheme.COL_FG, title);
        y += w.lineHeight(heading) + m.u(2);
        y += ui.centredParagraph(draw, body, cx, y, textW, w.lineHeight(body), ImGuiTheme.COL_FG2, text) + m.u(3);
        hint(draw, cx, y, ConnectionText.pipePath(view.pipePrefix()));
    }

    private static String paragraph(ConnectionsView view) {
        if (view.isScanning()) {
            return "Checking every open pipe that starts with the prefix.";
        }
        String base = "Is the game client running? Launch it from the BotWithUs launcher, "
                + "or check the prefix if you renamed the pipe.";
        return view.isAutoConnect() ? base : base + " Auto-connect is off, so use Scan now to look again.";
    }

    private void radar(ImDrawList draw, float cx, float cy, float r, boolean isSweeping) {
        draw.addCircle(cx, cy, r, ImGuiTheme.COL_BORDER);
        draw.addCircle(cx, cy, r * (1f - RADAR_INNER_INSET * 2f), ImGuiTheme.COL_BORDER);
        if (isSweeping) {
            float a0 = (float) ((ImGui.getTime() % SWEEP_PERIOD_S) / SWEEP_PERIOD_S * Math.PI * 2);
            draw.pathClear();
            draw.pathArcTo(cx, cy, r, a0, a0 + SWEEP_ARC);
            draw.pathStroke(ImGuiTheme.COL_INFO, 0, SWEEP_STROKE_PX);
        }
        ImFont icon = ui.fonts().body();
        float iw = ui.width(icon, Icons.PLUG);
        ui.text(draw, icon, cx - iw * 0.5f, cy - icon.getFontSize() * 0.5f * ICON_SCALE, ImGuiTheme.COL_FG2,
                Icons.PLUG);
    }

    private void hint(ImDrawList draw, float cx, float y, String text) {
        ImGuiTheme.Metrics m = ui.m();
        ImFont mono = ui.fonts().monoCaption();
        float tw = ui.width(mono, text);
        float h = m.controlSmallHeight();
        float x0 = cx - tw * 0.5f - m.u(2);
        draw.addRectFilled(x0, y, x0 + tw + m.u(4), y + h, ImGuiTheme.COL_SURFACE, m.radiusSmall());
        draw.addRect(x0 + 0.5f, y + 0.5f, x0 + tw + m.u(4) - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER,
                m.radiusSmall());
        ui.textCentredY(draw, mono, x0 + m.u(2), y, h, ImGuiTheme.COL_FG2, text);
    }
}
