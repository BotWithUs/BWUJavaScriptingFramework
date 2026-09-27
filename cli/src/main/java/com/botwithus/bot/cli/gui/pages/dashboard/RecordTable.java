package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import java.util.List;

/**
 * A dock tab's list of records, Logs or Events: a header that stays put over a
 * scrolling body of fixed-height mono rows. Only the rows in view are drawn,
 * so a full log buffer costs what a screenful does.
 */
final class RecordTable {

    /**
     * One column.
     *
     * @param width fixed width in pixels, or 0 for whatever is left (the last column)
     */
    record Col(String label, float width) { }

    /** Draws one record into its row. */
    @FunctionalInterface
    interface Painter<T> {
        /**
         * @param xs each column's left edge, then the right edge of the last
         */
        void paint(ImDrawList draw, T row, float[] xs, float y, float h);
    }

    private static final float ROW_EM = 1.6f;
    private static final float HEAD_EM = 2f;

    private final PanelChrome chrome;
    private final Controls ui;

    RecordTable(PanelChrome chrome) {
        this.chrome = chrome;
        this.ui = chrome.ui();
    }

    float rowHeight() {
        return ui.fonts().body().getFontSize() * ROW_EM;
    }

    /**
     * Draws the table {@code w} by {@code h} at the cursor.
     *
     * @param isFollowing keep the newest row, the last, in view
     */
    <T> void render(String id, List<Col> cols, List<T> rows, Painter<T> painter, boolean isFollowing,
                    String emptyText, float w, float h) {
        float headH = ui.fonts().body().getFontSize() * HEAD_EM;
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float[] xs = edges(cols, x + chrome.m().u(4), x + w - chrome.m().u(4));
        header(cols, xs, y, headH, w);
        ImGui.setCursorScreenPos(x, y + headH);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.beginChild(id, w, Math.max(1f, h - headH), false, ImGuiWindowFlags.None);
        ImGui.popStyleVar();
        if (rows.isEmpty()) {
            empty(emptyText);
        } else {
            body(rows, painter, xs, isFollowing);
        }
        ImGui.endChild();
    }

    private static float[] edges(List<Col> cols, float left, float right) {
        float[] xs = new float[cols.size() + 1];
        float cx = left;
        for (int i = 0; i < cols.size(); i++) {
            xs[i] = cx;
            cx += cols.get(i).width() > 0f ? cols.get(i).width() : Math.max(0f, right - cx);
        }
        xs[cols.size()] = right;
        return xs;
    }

    private void header(List<Col> cols, float[] xs, float y, float h, float w) {
        ImDrawList draw = ImGui.getWindowDrawList();
        for (int i = 0; i < cols.size(); i++) {
            ui.textCentredY(draw, ui.fonts().captionMedium(), xs[i], y, h, ImGuiTheme.COL_FG2, cols.get(i).label());
        }
        float left = xs[0] - chrome.m().u(4);
        draw.addLine(left, y + h, left + w, y + h, ImGuiTheme.COL_BORDER, chrome.m().hairline());
    }

    private <T> void body(List<T> rows, Painter<T> painter, float[] xs, boolean isFollowing) {
        float rowH = rowHeight();
        float viewH = ImGui.getWindowHeight();
        float scroll = ImGui.getScrollY();
        int first = Math.max(0, (int) (scroll / rowH));
        int last = Math.min(rows.size(), first + (int) Math.ceil(viewH / rowH) + 1);
        float top = ImGui.getCursorScreenPosY();
        ImDrawList draw = ImGui.getWindowDrawList();
        for (int i = first; i < last; i++) {
            painter.paint(draw, rows.get(i), xs, top + i * rowH, rowH);
        }
        ImGui.dummy(xs[xs.length - 1] - xs[0], rows.size() * rowH);
        if (isFollowing) {
            ImGui.setScrollY(ImGui.getScrollMaxY());
        }
    }

    private void empty(String text) {
        float h = ImGui.getWindowHeight();
        float w = ImGui.getWindowWidth();
        float x = ImGui.getWindowPosX() + (w - ui.width(ui.fonts().small(), text)) * 0.5f;
        ui.textCentredY(ImGui.getWindowDrawList(), ui.fonts().small(), x, ImGui.getWindowPosY(), h,
                ImGuiTheme.COL_FG2, text);
    }
}
