package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.List;

/**
 * "RPC latency": the busiest methods across every client in scope, pooled, with
 * a range bar per method from p50 to p99 and a tick at p95 on one shared log
 * scale, so a method with a long tail stands out without reading a number.
 */
final class RpcPanel {

    /** The spread bar's scale ends here, in milliseconds; slower values pin to the end. */
    static final double SCALE_MAX_MS = 200.0;

    private static final float ROW_EM = 2.267f;
    private static final float HEAD_EM = 2f;
    private static final float EMPTY_ROW_EM = 4.267f;
    private static final float NUM_EM = 3.6f;
    private static final float CALLS_EM = 4.4f;
    private static final float SPREAD_EM = 8f;
    private static final float BAR_EM = 0.667f;
    private static final float SPAN_EM = 0.4f;
    private static final float TRACK_EM = 0.133f;
    private static final float TICK_W_PX = 2f;
    private static final float LEGEND_SWATCH_EM = 1.2f;
    private static final int P50_COLUMN = 4;
    private static final String SPREAD_LABEL = "Spread 0–200 ms, log";

    private final PanelChrome chrome;
    private final Controls ui;
    private final DashTable table;

    RpcPanel(PanelChrome chrome) {
        this.chrome = chrome;
        this.ui = chrome.ui();
        this.table = new DashTable(ui);
    }

    void render(DashboardView view, Scope scope, String scopeLabel, float width) {
        chrome.begin("##rpc", width);
        String where = scope.isAll() ? "all clients" : scopeLabel;
        float y = chrome.header("RPC latency", "top " + view.rpc().size() + " by calls · " + where);
        legend(y);
        if (view.rpc().isEmpty()) {
            empty(view.isCollectingRpc());
        } else {
            rows(view.rpc());
        }
        chrome.end();
    }

    /** "▭ p50–p99 | p95" at the right of the header. */
    private void legend(float y) {
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont font = ui.fonts().caption();
        float fs = ui.fonts().body().getFontSize();
        float swatchW = fs * LEGEND_SWATCH_EM;
        float swatchH = fs * SPAN_EM;
        float gap = chrome.m().u(1.5f);
        float w = swatchW + gap + ui.width(font, "p50–p99") + chrome.m().u(3) + TICK_W_PX + gap
                + ui.width(font, "p95");
        float h = chrome.m().controlSmallHeight();
        float x = chrome.headerRight(w);
        float sy = y + (h - swatchH) * 0.5f;
        draw.addRectFilled(x, sy, x + swatchW, sy + swatchH, ImGuiTheme.COL_INFO_SOFT, swatchH * 0.5f);
        draw.addRect(x, sy, x + swatchW, sy + swatchH, ImGuiTheme.COL_INFO, swatchH * 0.5f);
        x += swatchW + gap;
        ui.textCentredY(draw, font, x, y, h, ImGuiTheme.COL_FG2, "p50–p99");
        x += ui.width(font, "p50–p99") + chrome.m().u(3);
        float tickH = fs * BAR_EM;
        draw.addRectFilled(x, y + (h - tickH) * 0.5f, x + TICK_W_PX, y + (h + tickH) * 0.5f, ImGuiTheme.COL_FG);
        ui.textCentredY(draw, font, x + TICK_W_PX + gap, y, h, ImGuiTheme.COL_FG2, "p95");
    }

    private void empty(boolean isCollecting) {
        String text = isCollecting
                ? "No RPC calls yet. They show here once a script talks to its client."
                : "RPC timing is off. Turn it on in Settings, under Diagnostics.";
        float h = ui.fonts().body().getFontSize() * EMPTY_ROW_EM;
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float w = ImGui.getWindowWidth();
        ImFont font = ui.fonts().small();
        ui.textCentredY(ImGui.getWindowDrawList(), font, x + (w - ui.width(font, text)) * 0.5f, y, h,
                ImGuiTheme.COL_FG2, text);
        ImGui.dummy(w, h);
    }

    private List<DashTable.Column> columns() {
        float fs = ui.fonts().body().getFontSize();
        return List.of(DashTable.Column.stretch("Method"),
                new DashTable.Column("Calls", fs * CALLS_EM, true),
                new DashTable.Column("Errors", fs * NUM_EM, true),
                new DashTable.Column("Avg", fs * NUM_EM, true),
                new DashTable.Column("p50", fs * NUM_EM, true),
                new DashTable.Column("p95", fs * NUM_EM, true),
                new DashTable.Column("p99", fs * NUM_EM, true),
                new DashTable.Column(SPREAD_LABEL, fs * SPREAD_EM + ui.width(ui.fonts().caption(), "0"), false));
    }

    private void rows(List<RpcRow> rows) {
        float fs = ui.fonts().body().getFontSize();
        List<DashTable.Column> columns = columns();
        if (!table.begin("##rpc-table", columns)) {
            return;
        }
        table.header(columns, fs * HEAD_EM, new boolean[0], -1, true);
        ImFont mono = ui.fonts().monoCaption();
        for (RpcRow row : rows) {
            table.row(fs * ROW_EM);
            DashTable.Cell method = table.cell(0);
            table.textFitted(new DashTable.Cell(method.x() + chrome.m().u(2), method.y(),
                    method.w() - chrome.m().u(2), method.h()), mono, ImGuiTheme.COL_FG, row.method());
            table.text(table.cell(1), mono, ImGuiTheme.COL_FG, DashFormat.count(row.calls()), true);
            table.text(table.cell(2), mono, row.errors() > 0 ? ImGuiTheme.COL_DANGER : ImGuiTheme.COL_FG3,
                    DashFormat.count(row.errors()), true);
            latency(table.cell(3), row.avgMs());
            latency(table.cell(P50_COLUMN), row.p50Ms());
            latency(table.cell(P50_COLUMN + 1), row.p95Ms());
            latency(table.cell(P50_COLUMN + 2), row.p99Ms());
            spread(table.cell(P50_COLUMN + 3), row);
        }
        table.end();
    }

    private void latency(DashTable.Cell cell, double ms) {
        table.text(cell, ui.fonts().monoCaption(), RpcRow.isHot(ms) ? ImGuiTheme.COL_WARN : ImGuiTheme.COL_FG,
                DashFormat.rpcMs(ms), true);
    }

    private void spread(DashTable.Cell cell, RpcRow row) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float fs = ui.fonts().body().getFontSize();
        float w = fs * SPREAD_EM;
        float x = cell.x();
        float mid = cell.y() + cell.h() * 0.5f;
        float track = fs * TRACK_EM;
        draw.addRectFilled(x, mid - track * 0.5f, x + w, mid + track * 0.5f, ImGuiTheme.COL_ELEVATED, track);
        float from = x + w * position(row.p50Ms());
        float to = Math.max(from + TICK_W_PX, x + w * position(row.p99Ms()));
        float half = fs * SPAN_EM * 0.5f;
        boolean isHot = row.isSpanHot();
        draw.addRectFilled(from, mid - half, to, mid + half,
                isHot ? ImGuiTheme.COL_WARN_SOFT : ImGuiTheme.COL_INFO_SOFT, half);
        draw.addRect(from, mid - half, to, mid + half, isHot ? ImGuiTheme.COL_WARN : ImGuiTheme.COL_INFO, half);
        float tick = x + w * position(row.p95Ms());
        float tickHalf = fs * BAR_EM * 0.5f;
        draw.addRectFilled(tick - TICK_W_PX * 0.5f, mid - tickHalf, tick + TICK_W_PX * 0.5f, mid + tickHalf,
                ImGuiTheme.COL_FG);
    }

    /** Where {@code ms} sits on the shared 0 to {@link #SCALE_MAX_MS} log scale, from 0 to 1. */
    static float position(double ms) {
        double p = Math.log10(1.0 + Math.max(0.0, ms)) / Math.log10(1.0 + SCALE_MAX_MS);
        return (float) Math.min(1.0, p);
    }
}
