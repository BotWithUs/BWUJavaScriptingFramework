package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.log.LogEntry;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.List;
import java.util.function.Function;

/** The dock's Logs tab: the log buffer for the page's scope, at the chosen level. */
final class LogsTab {

    private static final float LEVEL_EM = 3.6f;
    private static final float SOURCE_EM = 8f;
    private static final float CLIENT_EM = 8f;

    private final PanelChrome chrome;
    private final Controls ui;
    private final RecordTable table;

    LogsTab(PanelChrome chrome) {
        this.chrome = chrome;
        this.ui = chrome.ui();
        this.table = new RecordTable(chrome);
    }

    void render(LogsView logs, DashboardState state, Function<String, String> labels, float w, float h) {
        float fs = ui.fonts().body().getFontSize();
        float timeW = ui.width(ui.fonts().monoCaption(), "00:00:00.000") + chrome.m().u(4);
        List<RecordTable.Col> cols = List.of(new RecordTable.Col("Time", timeW),
                new RecordTable.Col("Level", fs * LEVEL_EM), new RecordTable.Col("Source", fs * SOURCE_EM),
                new RecordTable.Col("Client", fs * CLIENT_EM), new RecordTable.Col("Message", 0f));
        String empty = state.scope().isAll() ? "No log lines at this level." : "No log lines for this client.";
        table.render("##logs", cols, logs.lines(), (draw, e, xs, y, rh) -> row(draw, e, labels, xs, y, rh),
                state.isFollowing(), empty, w, h);
    }

    private void row(ImDrawList draw, LogEntry e, Function<String, String> labels, float[] xs, float y, float h) {
        ImFont mono = ui.fonts().monoCaption();
        String level = e.level() != null ? e.level() : "-";
        String client = e.connection() != null ? labels.apply(e.connection()) : "-";
        String message = e.message() != null ? e.message() : "";
        cell(draw, mono, xs, 0, y, h, ImGuiTheme.COL_FG3, DashFormat.clockMillis(e.timestamp()));
        cell(draw, ui.fonts().captionMedium(), xs, 1, y, h, levelColor(level), level);
        cell(draw, mono, xs, 2, y, h, ImGuiTheme.COL_FG, e.source() != null ? e.source() : "-");
        cell(draw, mono, xs, 3, y, h, ImGuiTheme.COL_FG2, client);
        cell(draw, mono, xs, 4, y, h, "ERROR".equals(level) ? ImGuiTheme.COL_DANGER : ImGuiTheme.COL_FG, message);
    }

    private void cell(ImDrawList draw, ImFont font, float[] xs, int i, float y, float h, int col, String text) {
        float room = xs[i + 1] - xs[i] - chrome.m().u(2);
        ui.textCentredY(draw, font, xs[i], y, h, col, ui.ellipsize(font, text, Math.max(0f, room)));
    }

    static int levelColor(String level) {
        return switch (level) {
            case "ERROR" -> ImGuiTheme.COL_DANGER;
            case "WARN" -> ImGuiTheme.COL_WARN;
            case "INFO" -> ImGuiTheme.COL_ACCENT;
            default -> ImGuiTheme.COL_FG3;
        };
    }

    /** Puts the shown lines on the clipboard, one tab-separated line each. */
    static void copy(LogsView logs) {
        StringBuilder sb = new StringBuilder();
        for (LogEntry e : logs.lines()) {
            sb.append(DashFormat.clockMillis(e.timestamp())).append('\t')
                    .append(e.level() != null ? e.level() : "-").append('\t')
                    .append(e.source() != null ? e.source() : "-").append('\t')
                    .append(e.connection() != null ? e.connection() : "-").append('\t')
                    .append(e.message() != null ? e.message() : "").append('\n');
        }
        ImGui.setClipboardText(sb.toString());
    }
}
