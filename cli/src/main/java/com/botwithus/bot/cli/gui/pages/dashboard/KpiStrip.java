package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.ArrayList;
import java.util.List;

/**
 * The four headline numbers: clients alive, scripts running, RPC calls a minute
 * and the slowest loop. One bordered strip, four cells side by side, or two by
 * two when the page is narrow, as the design's breakpoint does.
 */
final class KpiStrip {

    /** Below this page width, in body-font ems, the four cells wrap to two rows. */
    private static final float FOUR_ACROSS_MIN_EM = 44f;
    private static final int CELLS = 4;
    private static final int NARROW_COLUMNS = 2;

    /** A caption fragment and its colour; a cell's third line is a row of these. */
    private record Part(String text, int color) { }

    /** One cell's three lines. */
    private record Cell(String label, String value, String unit, List<Part> detail) { }

    private final PanelChrome chrome;
    private final Controls ui;

    KpiStrip(PanelChrome chrome) {
        this.chrome = chrome;
        this.ui = chrome.ui();
    }

    /** @param isReset whether the figures run from a reset rather than from the start */
    void render(Kpis kpis, boolean isReset, float width) {
        List<Cell> cells = List.of(clients(kpis), scripts(kpis), rpc(kpis, isReset), slowest(kpis));
        boolean isFourAcross = width >= ui.fonts().body().getFontSize() * FOUR_ACROSS_MIN_EM;
        int columns = isFourAcross ? CELLS : NARROW_COLUMNS;
        float cellW = width / columns;
        float cellH = cellHeight();
        int rows = CELLS / columns;
        chrome.begin("##kpis", width);
        float x0 = ImGui.getWindowPosX();
        float y0 = ImGui.getWindowPosY();
        ImDrawList draw = ImGui.getWindowDrawList();
        for (int i = 0; i < CELLS; i++) {
            float x = x0 + (i % columns) * cellW;
            float y = y0 + (float) (i / columns) * cellH;
            drawCell(draw, cells.get(i), x, y, cellW);
            dividers(draw, i, columns, x, y, cellW, cellH);
        }
        ImGui.dummy(width, cellH * rows);
        chrome.end();
    }

    private float cellHeight() {
        ImGuiTheme.Metrics m = chrome.m();
        return m.u(3) * 2f + ui.fonts().caption().getFontSize() * DashboardPage.LINE_HEIGHT * 2f
                + ui.fonts().titleMedium().getFontSize() * DashboardPage.LINE_HEIGHT;
    }

    private void dividers(ImDrawList draw, int i, int columns, float x, float y, float w, float h) {
        float hair = chrome.m().hairline();
        if (i % columns > 0) {
            draw.addLine(x, y, x, y + h, ImGuiTheme.COL_BORDER, hair);
        }
        if (i >= columns) {
            draw.addLine(x, y, x + w, y, ImGuiTheme.COL_BORDER, hair);
        }
    }

    private void drawCell(ImDrawList draw, Cell cell, float x, float y, float w) {
        ImGuiTheme.Metrics m = chrome.m();
        float px = x + m.u(4);
        float py = y + m.u(3);
        ImFont caption = ui.fonts().caption();
        ImFont value = ui.fonts().titleMedium();
        float captionH = caption.getFontSize() * DashboardPage.LINE_HEIGHT;
        ui.text(draw, caption, px, py, ImGuiTheme.COL_FG2, cell.label());
        float vy = py + captionH;
        ui.text(draw, value, px, vy, ImGuiTheme.COL_FG, cell.value());
        if (!cell.unit().isEmpty()) {
            ImFont small = ui.fonts().small();
            float ux = px + ui.width(value, cell.value()) + m.u(1);
            float uy = vy + value.getFontSize() - small.getFontSize();
            ui.text(draw, small, ux, uy, ImGuiTheme.COL_FG2, cell.unit());
        }
        float dy = vy + value.getFontSize() * DashboardPage.LINE_HEIGHT;
        float dx = px;
        float maxX = x + w - m.u(4);
        for (Part part : cell.detail()) {
            String text = ui.ellipsize(caption, part.text(), Math.max(0f, maxX - dx));
            ui.text(draw, caption, dx, dy, part.color(), text);
            dx += ui.width(caption, text) + m.u(2);
        }
    }

    // ── The four cells ──────────────────────────────────────────────────

    private static Cell clients(Kpis k) {
        List<Part> detail = new ArrayList<>();
        if (k.clients() == 0) {
            detail.add(new Part("no clients yet", ImGuiTheme.COL_FG2));
        } else if (k.reconnecting() == 1) {
            detail.add(new Part("1 reconnecting · attempt " + k.worstAttempt(), ImGuiTheme.COL_WARN));
        } else if (k.reconnecting() > 1) {
            detail.add(new Part(k.reconnecting() + " reconnecting", ImGuiTheme.COL_WARN));
        } else if (k.clientsAlive() == k.clients()) {
            detail.add(new Part("all responding", ImGuiTheme.COL_FG2));
        } else {
            detail.add(new Part((k.clients() - k.clientsAlive()) + " not responding", ImGuiTheme.COL_WARN));
        }
        return new Cell("Clients alive", Integer.toString(k.clientsAlive()), "/ " + k.clients(), detail);
    }

    private static Cell scripts(Kpis k) {
        List<Part> detail = new ArrayList<>();
        addCount(detail, k.stalled(), "stalled", ImGuiTheme.COL_WARN);
        addCount(detail, k.crashed(), "crashed", ImGuiTheme.COL_DANGER);
        addCount(detail, k.cutOff(), "cut off", ImGuiTheme.COL_DANGER);
        addCount(detail, k.stopped(), "stopped", ImGuiTheme.COL_FG2);
        if (detail.isEmpty()) {
            detail.add(new Part(k.runners() == 0 ? "none registered" : "all running", ImGuiTheme.COL_FG2));
        }
        return new Cell("Scripts running", Integer.toString(k.running()), "/ " + k.runners(), detail);
    }

    private static void addCount(List<Part> detail, int n, String what, int color) {
        if (n > 0) {
            detail.add(new Part(n + " " + what, color));
        }
    }

    private static Cell rpc(Kpis k, boolean isReset) {
        String since = isReset ? " since reset ·" : " since start ·";
        List<Part> detail = List.of(
                new Part(DashFormat.count(k.rpcCalls()) + since, ImGuiTheme.COL_FG2),
                new Part(DashFormat.count(k.rpcErrors()) + (k.rpcErrors() == 1 ? " error" : " errors"),
                        k.rpcErrors() > 0 ? ImGuiTheme.COL_DANGER : ImGuiTheme.COL_FG2));
        return new Cell("RPC calls / min", DashFormat.count(Math.round(k.rpcCallsPerMin())), "", detail);
    }

    private static Cell slowest(Kpis k) {
        return k.slowest()
                .map(r -> new Cell("Slowest loop (avg)", DashFormat.count(Math.round(r.avgMs())), "ms",
                        List.of(new Part(r.script() + " · " + r.clientLabel(), ImGuiTheme.COL_FG2))))
                .orElse(new Cell("Slowest loop (avg)", DashFormat.NONE, "",
                        List.of(new Part("nothing running", ImGuiTheme.COL_FG2))));
    }
}
