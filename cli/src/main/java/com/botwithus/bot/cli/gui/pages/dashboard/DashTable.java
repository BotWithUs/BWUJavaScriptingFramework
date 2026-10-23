package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiTableColumnFlags;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiTableRowFlags;

import java.util.List;

/**
 * The Dashboard's tables: an ImGui table for column sizing and clipping, with
 * every cell drawn by hand so text sits centred in the design's row heights and
 * numbers line up on the right. Rows are separated by hairlines, as in the design.
 */
final class DashTable {

    /**
     * One column.
     *
     * @param width     fixed width in pixels, or 0 to share what is left
     * @param isNumeric right-aligned, header included
     */
    record Column(String label, float width, boolean isNumeric) {

        static Column stretch(String label) {
            return new Column(label, 0f, false);
        }
    }

    /** Where a cell is on screen. */
    record Cell(float x, float y, float w, float h) { }

    private static final int STYLE_VARS = 1;
    private static final int STYLE_COLORS = 3;

    private final Controls ui;
    private float rowHeight;

    DashTable(Controls ui) {
        this.ui = ui;
    }

    /**
     * Opens a table across the current panel. Draws nothing and returns false
     * when ImGui has nothing to lay out, in which case {@link #end()} must not run.
     */
    boolean begin(String id, List<Column> columns) {
        ImGui.pushStyleVar(ImGuiStyleVar.CellPadding, ui.m().u(2), 0f);
        Controls.pushColor(ImGuiCol.TableBorderLight, ImGuiTheme.COL_BORDER);
        Controls.pushColor(ImGuiCol.TableBorderStrong, ImGuiTheme.COL_BORDER);
        Controls.pushColor(ImGuiCol.TableHeaderBg, 0);
        int flags = ImGuiTableFlags.BordersInnerH | ImGuiTableFlags.SizingFixedFit | ImGuiTableFlags.PadOuterX;
        if (!ImGui.beginTable(id, columns.size(), flags)) {
            popStyle();
            return false;
        }
        for (Column c : columns) {
            if (c.width() > 0f) {
                ImGui.tableSetupColumn(c.label(), ImGuiTableColumnFlags.WidthFixed, c.width());
            } else {
                ImGui.tableSetupColumn(c.label(), ImGuiTableColumnFlags.WidthStretch, 1f);
            }
        }
        return true;
    }

    void end() {
        ImGui.endTable();
        popStyle();
    }

    private static void popStyle() {
        ImGui.popStyleColor(STYLE_COLORS);
        ImGui.popStyleVar(STYLE_VARS);
    }

    /**
     * The header row: captions in the secondary colour, with a caret on the
     * sorted column. Columns in {@code sortable} can be clicked.
     *
     * @param sorted the sorted column's index, or -1
     * @return the index of the header clicked this frame, or -1
     */
    int header(List<Column> columns, float height, boolean[] sortable, int sorted, boolean isAscending) {
        ImGui.tableNextRow(ImGuiTableRowFlags.Headers, height);
        int clicked = -1;
        for (int i = 0; i < columns.size(); i++) {
            Cell cell = cell(i, height);
            Column c = columns.get(i);
            boolean isSortable = i < sortable.length && sortable[i];
            if (isSortable && headerButton(i, cell)) {
                clicked = i;
            }
            String caret = i == sorted ? (isAscending ? Icons.CARET_UP : Icons.CARET_DOWN) : "";
            headerLabel(cell, c, caret, isSortable && ImGui.isItemHovered());
        }
        return clicked;
    }

    private static boolean headerButton(int index, Cell cell) {
        ImGui.setCursorScreenPos(cell.x(), cell.y());
        return ImGui.invisibleButton("##th" + index, Math.max(1f, cell.w()), cell.h());
    }

    private void headerLabel(Cell cell, Column c, String caret, boolean isHovered) {
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont font = ui.fonts().captionMedium();
        int col = isHovered ? ImGuiTheme.COL_FG : ImGuiTheme.COL_FG2;
        float caretW = caret.isEmpty() ? 0f : ui.width(ui.fonts().caption(), caret) + ui.m().u(1);
        float labelW = ui.width(font, c.label());
        float x = c.isNumeric() ? cell.x() + cell.w() - labelW - caretW : cell.x();
        ui.textCentredY(draw, font, x, cell.y(), cell.h(), col, c.label());
        if (!caret.isEmpty()) {
            ui.textCentredY(draw, ui.fonts().caption(), x + labelW + ui.m().u(1), cell.y(), cell.h(),
                    ImGuiTheme.COL_FG, caret);
        }
    }

    /** Starts a body row {@code height} tall. */
    void row(float height) {
        rowHeight = height;
        ImGui.tableNextRow(ImGuiTableRowFlags.None, height);
    }

    /** Moves to column {@code index} of the current row and says where it is. */
    Cell cell(int index) {
        return cell(index, rowHeight);
    }

    private static Cell cell(int index, float height) {
        ImGui.tableSetColumnIndex(index);
        return new Cell(ImGui.getCursorScreenPosX(), ImGui.getCursorScreenPosY(),
                ImGui.getContentRegionAvailX(), height);
    }

    /** Text centred vertically in {@code cell}, on the left or, for numbers, the right. */
    void text(Cell cell, ImFont font, int col, String text, boolean isRight) {
        float x = isRight ? cell.x() + cell.w() - ui.width(font, text) : cell.x();
        ui.textCentredY(ImGui.getWindowDrawList(), font, x, cell.y(), cell.h(), col, text);
    }

    /** Text that is cut short with an ellipsis rather than spilling into the next column. */
    void textFitted(Cell cell, ImFont font, int col, String text) {
        text(cell, font, col, ui.ellipsize(font, text, cell.w()), false);
    }
}
