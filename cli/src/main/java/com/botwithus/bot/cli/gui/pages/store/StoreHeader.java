package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * The Store's title row: "Script Store", a status line with a dot (or a spinner
 * while the launcher is asked), and on the right a note on where installs go and
 * Refresh.
 */
final class StoreHeader {

    private static final String TITLE = "Script Store";
    private static final String REFRESH = "Refresh";
    private static final String WHERE_INSTALLS_GO = "Installs load on every connected client";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final StoreWidgets w;
    private final Controls ui;

    StoreHeader(StoreWidgets widgets) {
        this.w = widgets;
        this.ui = widgets.ui();
    }

    /** The header's height, top padding included. */
    float height() {
        ImGuiTheme.Metrics m = ui.m();
        return m.u(4) + m.controlHeight() + m.u(3);
    }

    /** Draws the header across the current window; Refresh is offered only with {@code showTools}. */
    void render(StoreView view, StoreActions actions, boolean showTools) {
        ImGuiTheme.Metrics m = ui.m();
        float rowH = m.controlHeight();
        float x = ImGui.getWindowPosX() + m.u(5);
        float y = ImGui.getWindowPosY() + m.u(4);
        float right = ImGui.getWindowPosX() + ImGui.getWindowWidth() - m.u(5);
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont title = ui.fonts().titleMedium();
        ui.textCentredY(draw, title, x, y, rowH, ImGuiTheme.COL_FG, TITLE);
        status(draw, view, x + ui.width(title, TITLE) + m.u(3), y, rowH);
        if (showTools) {
            tools(draw, view, actions, right, y, rowH);
        }
    }

    private void tools(ImDrawList draw, StoreView view, StoreActions actions, float right, float y, float rowH) {
        ImGuiTheme.Metrics m = ui.m();
        float bw = ui.buttonWidth(Icons.ROTATE, REFRESH, Tone.GHOST);
        ImGui.setCursorScreenPos(right - bw, y);
        boolean isIdle = !view.isRefreshing() && view.hasCatalogue();
        if (ui.button("##store-refresh", Icons.ROTATE, REFRESH, Tone.GHOST, isIdle)) {
            actions.refresh();
        }
        float noteW = ui.width(ui.fonts().caption(), WHERE_INSTALLS_GO);
        ui.textCentredY(draw, ui.fonts().caption(), right - bw - m.u(3) - noteW, y, rowH, ImGuiTheme.COL_FG2,
                WHERE_INSTALLS_GO);
    }

    /** The dot or spinner, and the words beside it. */
    private void status(ImDrawList draw, StoreView view, float x, float y, float rowH) {
        ImGuiTheme.Metrics m = ui.m();
        Line line = lineFor(view);
        float cy = y + rowH * 0.5f;
        float r = m.dot() * 0.5f;
        if (line.isBusy()) {
            w.spinner(draw, x + m.u(1.5f), cy, m.u(1.5f), ImGuiTheme.COL_INFO);
        } else {
            draw.addCircleFilled(x + r, cy, r, line.dot());
        }
        float tx = x + (line.isBusy() ? m.u(3) + m.u(1.5f) : m.dot() + m.u(1.5f));
        ui.textCentredY(draw, ui.fonts().caption(), tx, y, rowH, ImGuiTheme.COL_FG2, line.text());
    }

    private record Line(String text, int dot, boolean isBusy) {}

    private static Line lineFor(StoreView view) {
        return switch (view.status()) {
            case StoreStatus.Loading ignored ->
                    new Line("Asking the launcher for your scripts…", ImGuiTheme.COL_INFO, true);
            case StoreStatus.Ready r when r.isStale() ->
                    new Line("Launcher not answering · showing the last catalogue", ImGuiTheme.COL_WARN, false);
            case StoreStatus.Ready ignored when view.isRefreshing() ->
                    new Line("Signed in · refreshing…", ImGuiTheme.COL_ACCENT, true);
            case StoreStatus.Ready r -> new Line("Signed in · synced " + CLOCK.format(r.syncedAt()),
                    ImGuiTheme.COL_ACCENT, false);
            case StoreStatus.Unavailable u -> new Line(unavailable(u.notice().reason()), ImGuiTheme.COL_FG3, false);
        };
    }

    private static String unavailable(StoreStatus.Reason reason) {
        return switch (reason) {
            case LAUNCHER_NOT_RUNNING -> "Launcher not running";
            case SIGNED_OUT -> "Signed out";
            case NO_SUBSCRIPTION -> "No BotWithUs subscription";
            case FAILED -> "Store unavailable";
        };
    }
}
