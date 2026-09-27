package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.cli.gui.CategoryStyle;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Segment;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.pages.dashboard.DashboardState.RunnerColumn;
import com.botwithus.bot.cli.gui.pages.dashboard.DashboardState.RunnerFilter;
import com.botwithus.bot.cli.gui.runners.RunnerStatus;
import com.botwithus.bot.cli.gui.usermode.PulseLaneView;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * "Script runners": every script on every client in scope, one row each, with
 * its loop timing, its pulse lane and Settings / Stop or Run / Thread dump.
 */
final class RunnersPanel {

    private static final float ROW_EM = 2.267f;
    private static final float HEAD_EM = 2f;
    private static final float EMPTY_ROW_EM = 4.267f;
    private static final float TILE_EM = 1.467f;
    private static final float LANE_W_EM = 4.8f;
    private static final float LANE_H_EM = 1.067f;
    private static final float CLIENT_EM = 6.5f;
    private static final float STATE_EM = 6.6f;
    private static final float LOOPS_EM = 3.8f;
    private static final float TIME_EM = 3.4f;
    private static final float MAX_EM = 3.6f;
    private static final int ACTIONS = 3;
    private static final int LANE_COLUMN = 7;
    private static final String FOOTER = "Loop times come from each runner's ScriptProfiler. The pulse shows"
            + " the last 24 loops, newest on the right; amber means over 2x the average.";
    private static final String FOOTER_OFF = " Loop timing is off in Settings, so only the pulse and the last"
            + " loop update.";
    private static final List<RunnerColumn> SORTABLE = List.of(RunnerColumn.SCRIPT, RunnerColumn.CLIENT,
            RunnerColumn.STATE, RunnerColumn.LOOPS, RunnerColumn.AVG, RunnerColumn.LAST, RunnerColumn.MAX);

    private final PanelChrome chrome;
    private final Controls ui;
    private final DashTable table;
    private final PulseLaneView lane;

    RunnersPanel(PanelChrome chrome) {
        this.chrome = chrome;
        this.ui = chrome.ui();
        this.table = new DashTable(ui);
        this.lane = new PulseLaneView(ui);
    }

    void render(DashboardView view, DashboardState state, DashboardActions actions, Instant now, float width) {
        chrome.begin("##runners", width);
        header(view.runners(), state);
        List<RunnerRow> rows = state.sorted(view.runners());
        if (rows.isEmpty()) {
            empty(view.runners().isEmpty());
        } else {
            rows(rows, state, actions, now);
        }
        chrome.footer(view.isCollectingLoops() ? FOOTER : FOOTER + FOOTER_OFF);
        chrome.end();
    }

    private void header(List<RunnerRow> all, DashboardState state) {
        long running = all.stream().filter(RunnerFilter.RUNNING::admits).count();
        long problems = all.stream().filter(RunnerFilter.PROBLEMS::admits).count();
        float y = chrome.header("Script runners", all.size() + " registered");
        List<Segment> segments = List.of(
                new Segment("All", Integer.toString(all.size()), false),
                new Segment("Running", Long.toString(running), false),
                new Segment("Problems", Long.toString(problems), false));
        float w = ui.segmentedWidth(segments);
        float back = ImGui.getCursorScreenPosY();
        ImGui.setCursorScreenPos(chrome.headerRight(w), y);
        int clicked = ui.segmented("##run-filter", segments, state.filter().ordinal(), 0f,
                chrome.m().controlSmallHeight());
        if (clicked >= 0) {
            state.setFilter(RunnerFilter.values()[clicked]);
        }
        ImGui.setCursorScreenPos(ImGui.getWindowPosX(), back);
    }

    private void empty(boolean isNoneAtAll) {
        String text = isNoneAtAll
                ? "No scripts registered yet. Start one from Clients or Installed scripts."
                : "No runners match this view.";
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
        float actions = chrome.iconButtonSize() * ACTIONS + chrome.m().u(0.5f) * (ACTIONS - 1);
        return List.of(DashTable.Column.stretch("Script"),
                new DashTable.Column("Client", fs * CLIENT_EM, false),
                new DashTable.Column("State", fs * STATE_EM, false),
                new DashTable.Column("Loops", fs * LOOPS_EM, true),
                new DashTable.Column("Avg", fs * TIME_EM, true),
                new DashTable.Column("Last", fs * TIME_EM, true),
                new DashTable.Column("Max", fs * MAX_EM, true),
                new DashTable.Column("Pulse", fs * LANE_W_EM, false),
                new DashTable.Column("", actions, false));
    }

    private void rows(List<RunnerRow> rows, DashboardState state, DashboardActions actions, Instant now) {
        float fs = ui.fonts().body().getFontSize();
        List<DashTable.Column> columns = columns();
        if (!table.begin("##runner-table", columns)) {
            return;
        }
        boolean[] sortable = new boolean[columns.size()];
        for (int i = 0; i < SORTABLE.size(); i++) {
            sortable[i] = true;
        }
        int clicked = table.header(columns, fs * HEAD_EM, sortable, SORTABLE.indexOf(state.sortColumn()),
                state.isSortAscending());
        if (clicked >= 0) {
            state.sortBy(SORTABLE.get(clicked));
        }
        for (RunnerRow row : rows) {
            ImGui.pushID(row.ref().client().value() + "/" + row.script());
            row(row, state, actions, now, fs * ROW_EM);
            ImGui.popID();
        }
        table.end();
    }

    private void row(RunnerRow row, DashboardState state, DashboardActions actions, Instant now, float h) {
        table.row(h);
        scriptCell(table.cell(0), row);
        table.textFitted(table.cell(1), ui.fonts().small(), ImGuiTheme.COL_FG, row.clientLabel());
        stateCell(table.cell(2), row, now);
        ImFont mono = ui.fonts().monoCaption();
        table.text(table.cell(3), mono, row.loops() > 0 ? ImGuiTheme.COL_FG : ImGuiTheme.COL_FG3,
                DashFormat.count(row.loops()), true);
        table.text(table.cell(4), mono, ImGuiTheme.COL_FG, DashFormat.loopMs(row.avgMs()), true);
        table.text(table.cell(5), mono, row.isLastHot() ? ImGuiTheme.COL_WARN : ImGuiTheme.COL_FG,
                DashFormat.loopMs(row.lastMs()), true);
        maxCell(table.cell(6), row);
        laneCell(table.cell(LANE_COLUMN), row);
        actionCell(table.cell(LANE_COLUMN + 1), row, state, actions);
    }

    private void scriptCell(DashTable.Cell cell, RunnerRow row) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float fs = ui.fonts().body().getFontSize();
        float tile = fs * TILE_EM;
        float x = cell.x() + chrome.m().u(2);
        float ty = cell.y() + (cell.h() - tile) * 0.5f;
        draw.addRectFilled(x, ty, x + tile, ty + tile, ImGuiTheme.COL_ELEVATED, chrome.m().radiusSmall());
        String icon = CategoryStyle.icon(row.category());
        ImFont iconFont = ui.fonts().caption();
        ui.textCentredY(draw, iconFont, x + (tile - ui.width(iconFont, icon)) * 0.5f, cell.y(), cell.h(),
                ImGuiTheme.COL_FG2, icon);
        float nx = x + tile + chrome.m().u(2);
        float room = cell.x() + cell.w() - nx;
        String version = row.version().isBlank() ? "" : "v" + row.version();
        float versionW = version.isEmpty() ? 0f : ui.width(ui.fonts().monoCaption(), version) + chrome.m().u(1.5f);
        if (ui.width(ui.fonts().small(), row.script()) + versionW > room) {
            version = "";
            versionW = 0f;
        }
        String name = ui.ellipsize(ui.fonts().small(), row.script(), Math.max(0f, room - versionW));
        ui.textCentredY(draw, ui.fonts().small(), nx, cell.y(), cell.h(), ImGuiTheme.COL_FG, name);
        if (!version.isEmpty()) {
            float vx = nx + ui.width(ui.fonts().small(), name) + chrome.m().u(1.5f);
            ui.textCentredY(draw, ui.fonts().monoCaption(), vx, cell.y(), cell.h(), ImGuiTheme.COL_FG2, version);
        }
    }

    private void stateCell(DashTable.Cell cell, RunnerRow row, Instant now) {
        ImDrawList draw = ImGui.getWindowDrawList();
        switch (row.status()) {
            case RUNNING -> chrome.status(draw, cell.x(), cell.y(), cell.h(), "Running", ImGuiTheme.COL_ACCENT);
            case STOPPED -> chrome.status(draw, cell.x(), cell.y(), cell.h(), "Stopped", ImGuiTheme.COL_FG2);
            case STALLED -> chrome.status(draw, cell.x(), cell.y(), cell.h(), stalledLabel(row, now),
                    ImGuiTheme.COL_WARN);
            case CRASHED -> chrome.status(draw, cell.x(), cell.y(), cell.h(), "Crashed ×" + row.crashes(),
                    ImGuiTheme.COL_DANGER);
            case CUT_OFF -> chrome.status(draw, cell.x(), cell.y(), cell.h(), "Cut off", ImGuiTheme.COL_DANGER);
        }
    }

    private static String stalledLabel(RunnerRow row, Instant now) {
        return row.stalledSince()
                .map(since -> "Stalled " + DashFormat.span(Duration.between(since, now)))
                .orElse("Stalled");
    }

    private void maxCell(DashTable.Cell cell, RunnerRow row) {
        boolean isOutlier = row.isMaxOutlier();
        table.text(cell, ui.fonts().monoCaption(), isOutlier ? ImGuiTheme.COL_WARN : ImGuiTheme.COL_FG,
                DashFormat.loopMs(row.maxMs()), true);
        if (isOutlier && ImGui.isMouseHoveringRect(cell.x(), cell.y(), cell.x() + cell.w(), cell.y() + cell.h())) {
            ImGui.setTooltip("The worst loop is over 5x the average.");
        }
    }

    private void laneCell(DashTable.Cell cell, RunnerRow row) {
        float fs = ui.fonts().body().getFontSize();
        float w = Math.min(cell.w(), fs * LANE_W_EM);
        float h = fs * LANE_H_EM;
        float y = cell.y() + (cell.h() - h) * 0.5f;
        ImDrawList draw = ImGui.getWindowDrawList();
        boolean isLooping = row.status() == RunnerStatus.RUNNING || row.status() == RunnerStatus.STALLED;
        if (isLooping && row.lane().length > 0) {
            lane.running(draw, cell.x(), y, w, h, row.lane(), row.avgMs());
        } else {
            lane.flat(draw, cell.x(), y, w, h);
        }
    }

    private void actionCell(DashTable.Cell cell, RunnerRow row, DashboardState state, DashboardActions actions) {
        float s = chrome.iconButtonSize();
        float gap = chrome.m().u(0.5f);
        float y = cell.y() + (cell.h() - s) * 0.5f;
        ImGui.setCursorScreenPos(cell.x(), y);
        if (chrome.iconButton("##cfg", Icons.SLIDERS, ImGuiTheme.COL_FG2, ImGuiTheme.COL_ELEVATED,
                row.hasSettings(), "Settings")) {
            actions.openSettings(row.ref());
        }
        ImGui.setCursorScreenPos(cell.x() + s + gap, y);
        runOrStop(row, actions);
        ImGui.setCursorScreenPos(cell.x() + (s + gap) * 2f, y);
        if (chrome.iconButton("##dump", Icons.LAYER_GROUP, ImGuiTheme.COL_FG2, ImGuiTheme.COL_ELEVATED,
                true, "Thread dump")) {
            actions.threadDump(row.ref());
            state.showTab(DashboardState.DockTab.CONSOLE);
        }
    }

    private void runOrStop(RunnerRow row, DashboardActions actions) {
        switch (row.status()) {
            case RUNNING, STALLED -> {
                if (chrome.iconButton("##stop", Icons.STOP, ImGuiTheme.COL_DANGER, ImGuiTheme.COL_DANGER_SOFT,
                        true, "Stop")) {
                    actions.stop(row.ref());
                }
            }
            case CRASHED -> {
                if (chrome.iconButton("##run", Icons.REDO, ImGuiTheme.COL_ACCENT, ImGuiTheme.COL_ACCENT_SOFT,
                        true, "Restart")) {
                    actions.run(row.ref());
                }
            }
            case STOPPED, CUT_OFF -> {
                boolean canRun = row.status() == RunnerStatus.STOPPED;
                if (chrome.iconButton("##run", Icons.PLAY, ImGuiTheme.COL_ACCENT, ImGuiTheme.COL_ACCENT_SOFT,
                        canRun, canRun ? "Run" : "Cut off until the host restarts")) {
                    actions.run(row.ref());
                }
            }
        }
    }
}
