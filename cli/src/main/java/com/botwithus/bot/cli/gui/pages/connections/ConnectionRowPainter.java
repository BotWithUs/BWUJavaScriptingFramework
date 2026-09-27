package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.cli.GameState;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.pages.connections.TableColumns.Cell;
import com.botwithus.bot.cli.gui.pages.connections.TableColumns.Column;
import com.botwithus.bot.cli.gui.pages.connections.TableColumns.Layout;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.Locale;

/**
 * Paints one Connections row: the console-target radio, the cells, and the
 * row's own actions on the right. A closed client's cells are dimmed: it is
 * remembered, not live.
 */
final class ConnectionRowPainter {

    private static final String UNKNOWN_ACCOUNT = "Unknown account";
    private static final String UUID_ON_CONNECT = "UUID on connect";
    private static final String MEMBERS = "members";
    private static final String MS = " ms";

    private final ConnectionWidgets w;
    private final Controls ui;
    private final ConnectionsModel model;

    ConnectionRowPainter(ConnectionWidgets widgets, ConnectionsModel model) {
        this.w = widgets;
        this.ui = widgets.ui();
        this.model = model;
    }

    /**
     * Paints {@code row} between x0 and x1 at y, {@code h} tall.
     *
     * @return true when the row itself, not one of its controls, was clicked
     */
    boolean paint(ConnectionRow row, Layout layout, float x0, float x1, float y, float h, boolean isSelected) {
        ImDrawList draw = ImGui.getWindowDrawList();
        boolean isHovered = ImGui.isWindowHovered() && ImGui.isMouseHoveringRect(x0, y, x1, y + h);
        int bg = isSelected ? ConnectionWidgets.COL_ROW_SELECTED : isHovered ? ConnectionWidgets.COL_ROW_HOVER : 0;
        if (bg != 0) {
            draw.addRectFilled(x0 + 1f, y, x1 - 1f, y + h, bg);
        }
        target(row, layout.cell(Column.TARGET), x0, y, h);
        actions(row, layout.cell(Column.ACTIONS), x0, y, h);
        float alpha = row.group() == RowGroup.CLOSED ? ConnectionWidgets.DIM_ALPHA : 1f;
        account(draw, row, layout.cell(Column.ACCOUNT), x0, y, h, alpha);
        mono(draw, layout.cell(Column.PIPE), x0, y, h, row.pipe().orElse(ConnectionText.NONE),
                row.pipe().isPresent() ? ImGuiTheme.COL_FG : ImGuiTheme.COL_FG3, alpha, false);
        if (layout.shows(Column.WORLD)) {
            mono(draw, layout.cell(Column.WORLD), x0, y, h, ConnectionText.world(row.world()), ImGuiTheme.COL_FG2,
                    alpha, false);
        }
        if (layout.shows(Column.GAME)) {
            game(draw, row, layout.cell(Column.GAME), x0, y, h, alpha);
        }
        w.linkState(draw, x0 + layout.cell(Column.LINK).x(), y, h, row.link());
        if (layout.shows(Column.RPC)) {
            rpc(draw, row, layout.cell(Column.RPC), x0, y, h);
        }
        if (layout.shows(Column.UP)) {
            up(draw, row, layout.cell(Column.UP), x0, y, h, alpha);
        }
        return isHovered && ImGui.isMouseClicked(0) && !ImGui.isAnyItemHovered();
    }

    // ── Controls ───────────────────────────────────────────────────────────

    private void target(ConnectionRow row, Cell cell, float x0, float y, float h) {
        float size = w.radioSize();
        ImGui.setCursorScreenPos(x0 + cell.x() + (cell.width() - size) * 0.5f, y + (h - size) * 0.5f);
        if (w.radio("##target", row.isConsoleTarget(), row.can(RowAction.CONSOLE_TARGET))) {
            model.setConsoleTarget(row);
        }
    }

    /** The row's actions, right-aligned in their cell. */
    private void actions(ConnectionRow row, Cell cell, float x0, float y, float h) {
        ImGuiTheme.Metrics m = ui.m();
        float right = x0 + cell.x() + cell.width();
        float bh = m.controlSmallHeight();
        float by = y + (h - bh) * 0.5f;
        if (row.can(RowAction.CONNECT)) {
            button(row, right, by, "##connect", Icons.LINK, "Connect");
        } else if (row.can(RowAction.RETRY_NOW)) {
            button(row, right, by, "##retry", null, "Retry now");
        } else if (row.can(RowAction.FORGET)) {
            ImGui.setCursorScreenPos(right - w.textButtonWidth("Forget"), by);
            if (w.textButton("##forget", "Forget", bh)) {
                model.forget(row);
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Remove from this list. Its saved scripts are kept.");
            }
        } else if (row.can(RowAction.DISCONNECT)) {
            liveButtons(row, right, by);
        }
    }

    private void button(ConnectionRow row, float right, float y, String id, String icon, String label) {
        ImGui.setCursorScreenPos(right - ui.buttonWidth(icon, label, Tone.GHOST), y);
        if (ui.button(id, icon, label, Tone.GHOST, true, ui.m().controlSmallHeight())) {
            if (row.can(RowAction.CONNECT)) {
                model.connect(row);
            } else {
                model.retryNow(row);
            }
        }
    }

    private void liveButtons(ConnectionRow row, float right, float y) {
        float size = w.iconButtonSize();
        String who = row.label();
        ImGui.setCursorScreenPos(right - size, y);
        if (w.iconButton("##disconnect", Icons.POWER, false, true, "Disconnect " + who)) {
            model.disconnect(row);
        }
        if (row.can(RowAction.OUTPUT_FILTER)) {
            ImGui.setCursorScreenPos(right - size * 2f - ui.m().u(0.5f), y);
            String tip = row.isOutputFilter() ? "Show output from every connection"
                    : "Only show this connection's output in the console";
            if (w.iconButton("##filter", Icons.FILTER, row.isOutputFilter(), false, tip)) {
                model.toggleOutputFilter(row);
            }
        }
    }

    // ── Cells ──────────────────────────────────────────────────────────────

    private void account(ImDrawList draw, ConnectionRow row, Cell cell, float x0, float y, float h, float alpha) {
        ImFont top = ui.fonts().small();
        ImFont bottom = ui.fonts().monoCaption();
        float topH = ConnectionsTable.cellLine(top);
        float bottomH = ConnectionsTable.cellLine(bottom);
        float ty = y + (h - topH - bottomH) * 0.5f;
        float x = x0 + cell.x();
        String name = row.account().orElse(UNKNOWN_ACCOUNT);
        int nameCol = row.account().isPresent() ? ImGuiTheme.COL_FG : ImGuiTheme.COL_FG3;
        ui.textCentredY(draw, top, x, ty, topH, Controls.scaleAlpha(nameCol, alpha),
                ui.ellipsize(top, name, cell.width()));
        String id = row.accountUuid().map(ConnectionText::shortUuid)
                .orElse(row.group() == RowGroup.FOUND ? UUID_ON_CONNECT : ConnectionText.NONE);
        ui.textCentredY(draw, bottom, x, ty + topH, bottomH, Controls.scaleAlpha(ImGuiTheme.COL_FG2, alpha),
                ui.ellipsize(bottom, id, cell.width()));
    }

    private void mono(ImDrawList draw, Cell cell, float x0, float y, float h, String text, int col, float alpha,
                      boolean isRight) {
        ImFont font = ui.fonts().monoCaption();
        String shown = ui.ellipsize(font, text, cell.width());
        float x = isRight ? x0 + cell.x() + cell.width() - ui.width(font, shown) : x0 + cell.x();
        ui.textCentredY(draw, font, x, y, h, Controls.scaleAlpha(col, alpha), shown);
    }

    private void game(ImDrawList draw, ConnectionRow row, Cell cell, float x0, float y, float h, float alpha) {
        ImFont font = ui.fonts().caption();
        String text = ConnectionText.game(row.game());
        int col = row.game().state() == GameState.IN_GAME ? ImGuiTheme.COL_FG : ImGuiTheme.COL_FG2;
        float x = x0 + cell.x();
        ui.textCentredY(draw, font, x, y, h, Controls.scaleAlpha(col, alpha), text);
        if (ConnectionText.isMemberNote(row.game())) {
            float mx = x + ui.width(font, text) + ui.m().u(1);
            if (mx + ui.width(font, MEMBERS) <= x + cell.width()) {
                ui.textCentredY(draw, font, mx, y, h, Controls.scaleAlpha(ImGuiTheme.COL_FG2, alpha), MEMBERS);
            }
        }
    }

    private void rpc(ImDrawList draw, ConnectionRow row, Cell cell, float x0, float y, float h) {
        if (row.stats().isEmpty()) {
            mono(draw, cell, x0, y, h, ConnectionText.NONE, ImGuiTheme.COL_FG3, 1f, true);
            return;
        }
        ImFont font = ui.fonts().monoCaption();
        String number = String.format(Locale.ROOT, "%.1f", row.stats().get().avgRpcMs());
        float right = x0 + cell.x() + cell.width();
        float unitX = right - ui.width(font, MS);
        ui.textCentredY(draw, font, unitX, y, h, ImGuiTheme.COL_FG3, MS);
        ui.textCentredY(draw, font, unitX - ui.width(font, number), y, h, ImGuiTheme.COL_FG, number);
    }

    private void up(ImDrawList draw, ConnectionRow row, Cell cell, float x0, float y, float h, float alpha) {
        String text;
        int col = ImGuiTheme.COL_FG;
        switch (row.link()) {
            case LinkState.NotResponding n -> {
                text = ConnectionText.downFor(n.downFor());
                col = ImGuiTheme.COL_WARN;
            }
            case LinkState.Closed closed -> text = ConnectionText.ago(closed.ago());
            case LinkState.Connected _, LinkState.Identifying _, LinkState.Resuming _ ->
                    text = row.stats().map(stats -> ConnectionText.uptime(stats.uptime())).orElse(ConnectionText.NONE);
            case LinkState.Found _ -> {
                text = ConnectionText.NONE;
                col = ImGuiTheme.COL_FG3;
            }
        }
        mono(draw, cell, x0, y, h, text, col, alpha, true);
    }
}
