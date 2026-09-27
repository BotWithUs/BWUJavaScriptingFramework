package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.List;

/** The dock's Events tab: the host event history for the page's scope, oldest first. */
final class EventsTab {

    private static final float EVENT_EM = 13f;
    private static final float CLIENT_EM = 8f;
    private static final int EVENT_COLOR = ImGuiTheme.COL_MAGENTA;

    private final PanelChrome chrome;
    private final Controls ui;
    private final RecordTable table;

    EventsTab(PanelChrome chrome) {
        this.chrome = chrome;
        this.ui = chrome.ui();
        this.table = new RecordTable(chrome);
    }

    void render(List<EventRow> events, DashboardState state, float w, float h) {
        float fs = ui.fonts().body().getFontSize();
        float timeW = ui.width(ui.fonts().monoCaption(), "00:00:00") + chrome.m().u(4);
        List<RecordTable.Col> cols = List.of(new RecordTable.Col("Time", timeW),
                new RecordTable.Col("Event", fs * EVENT_EM), new RecordTable.Col("Client", fs * CLIENT_EM),
                new RecordTable.Col("Detail", 0f));
        String empty = state.scope().isAll() ? "No host events yet." : "No events for this client yet.";
        table.render("##events", cols, events, this::row, true, empty, w, h);
    }

    private void row(ImDrawList draw, EventRow e, float[] xs, float y, float h) {
        ImFont mono = ui.fonts().monoCaption();
        cell(draw, mono, xs, 0, y, h, ImGuiTheme.COL_FG3, DashFormat.clock(e.at()));
        cell(draw, mono, xs, 1, y, h, EVENT_COLOR, e.type());
        cell(draw, mono, xs, 2, y, h, ImGuiTheme.COL_FG2, e.clientLabel());
        cell(draw, mono, xs, 3, y, h, ImGuiTheme.COL_FG, e.detail());
    }

    private void cell(ImDrawList draw, ImFont font, float[] xs, int i, float y, float h, int col, String text) {
        float room = xs[i + 1] - xs[i] - chrome.m().u(2);
        ui.textCentredY(draw, font, xs[i], y, h, col, ui.ellipsize(font, text, Math.max(0f, room)));
    }

    static void copy(List<EventRow> events) {
        StringBuilder sb = new StringBuilder();
        for (EventRow e : events) {
            sb.append(DashFormat.clock(e.at())).append('\t').append(e.type()).append('\t')
                    .append(e.clientLabel()).append('\t').append(e.detail()).append('\n');
        }
        ImGui.setClipboardText(sb.toString());
    }
}
