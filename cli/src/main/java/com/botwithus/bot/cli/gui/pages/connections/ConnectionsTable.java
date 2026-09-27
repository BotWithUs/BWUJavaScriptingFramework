package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.pages.connections.TableColumns.Column;
import com.botwithus.bot.cli.gui.pages.connections.TableColumns.Layout;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImDrawFlags;

import java.util.List;
import java.util.Locale;

/**
 * The one table: a titled group per stage of a pipe's life (connected, found,
 * closed), the column heads under the first, and a row per client or pipe.
 * Under it, the legend says what the console target and the output filter are
 * doing right now.
 */
final class ConnectionsTable {

    private static final float ROW_EM = 3.2f;
    private static final float HEAD_EM = 2.133f;
    private static final float GROUP_EM = 2f;
    private static final float CELL_LINE = 1.35f;
    private static final String CLEAR = "Clear filter";

    private final ConnectionWidgets w;
    private final Controls ui;
    private final ConnectionsModel model;
    private final RowSelection selection;
    private final ConnectionRowPainter painter;

    ConnectionsTable(ConnectionWidgets widgets, ConnectionsModel model, RowSelection selection) {
        this.w = widgets;
        this.ui = widgets.ui();
        this.model = model;
        this.selection = selection;
        this.painter = new ConnectionRowPainter(widgets, model);
    }

    /**
     * Draws the table and the legend at the cursor, full width.
     *
     * @return true when "Clear filter" was clicked because nothing matched
     */
    boolean render(ConnectionsView view, List<ConnectionRow> shown) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float width = ImGui.getContentRegionAvailX();
        if (shown.isEmpty()) {
            return nothingMatches(x, y, width);
        }
        ImDrawList draw = ImGui.getWindowDrawList();
        Layout layout = TableColumns.fit(width, ui.fonts().body().getFontSize());
        // Rows draw on the upper channel so the box, sized once they are known, goes under them.
        draw.channelsSplit(2);
        draw.channelsSetCurrent(1);
        float bottom = groups(shown, layout, x, width, y);
        draw.channelsSetCurrent(0);
        float r = ui.m().radiusLarge();
        draw.addRectFilled(x, y, x + width, bottom, ImGuiTheme.COL_SURFACE, r);
        draw.channelsSetCurrent(1);
        draw.addRect(x + 0.5f, y + 0.5f, x + width - 0.5f, bottom - 0.5f, ImGuiTheme.COL_BORDER, r);
        draw.channelsMerge();
        float legendTop = bottom + ui.m().u(3);
        float legendBottom = legend(view, x, legendTop, width);
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, legendBottom - y);
        return false;
    }

    private float groups(List<ConnectionRow> shown, Layout layout, float x, float width, float y) {
        float cy = y;
        boolean isFirst = true;
        for (RowGroup group : RowGroup.values()) {
            List<ConnectionRow> rows = shown.stream().filter(row -> row.group() == group).toList();
            if (rows.isEmpty()) {
                continue;
            }
            cy = groupHeader(group, rows.size(), x, width, cy, isFirst);
            if (isFirst) {
                cy = columnHeads(layout, x, cy);
                isFirst = false;
            }
            cy = rows(rows, layout, x, width, cy);
        }
        return cy;
    }

    private float groupHeader(RowGroup group, int count, float x, float width, float y, boolean isFirst) {
        ImGuiTheme.Metrics m = ui.m();
        float h = ui.fonts().body().getFontSize() * GROUP_EM;
        ImDrawList draw = ImGui.getWindowDrawList();
        int corners = isFirst ? ImDrawFlags.RoundCornersTop : ImDrawFlags.RoundCornersNone;
        draw.addRectFilled(x + 1f, y + (isFirst ? 1f : 0f), x + width - 1f, y + h, ImGuiTheme.COL_BG,
                m.radiusLarge(), corners);
        draw.addLine(x, y + h - m.hairline(), x + width, y + h - m.hairline(), ImGuiTheme.COL_BORDER, m.hairline());
        ImFont font = ui.fonts().captionMedium();
        String title = group.title().toUpperCase(Locale.ROOT);
        ui.textCentredY(draw, font, x + m.u(4), y, h, ImGuiTheme.COL_FG3, title);
        ui.textCentredY(draw, ui.fonts().monoCaption(), x + m.u(4) + ui.width(font, title) + m.u(2), y, h,
                ImGuiTheme.COL_FG3, Integer.toString(count));
        return y + h;
    }

    private float columnHeads(Layout layout, float x, float y) {
        ImGuiTheme.Metrics m = ui.m();
        float h = ui.fonts().body().getFontSize() * HEAD_EM;
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont font = ui.fonts().captionMedium();
        head(draw, font, layout, Column.ACCOUNT, x, y, h, "Account", false);
        head(draw, font, layout, Column.PIPE, x, y, h, "Pipe", false);
        head(draw, font, layout, Column.WORLD, x, y, h, "World", false);
        head(draw, font, layout, Column.GAME, x, y, h, "Game", false);
        head(draw, font, layout, Column.LINK, x, y, h, "Link", false);
        head(draw, font, layout, Column.RPC, x, y, h, "RPC", true);
        head(draw, font, layout, Column.UP, x, y, h, "Up", true);
        draw.addLine(x, y + h - m.hairline(), x + ImGui.getContentRegionAvailX(), y + h - m.hairline(),
                ImGuiTheme.COL_BORDER, m.hairline());
        return y + h;
    }

    private void head(ImDrawList draw, ImFont font, Layout layout, Column column, float x, float y, float h,
                      String label, boolean isRight) {
        if (!layout.shows(column)) {
            return;
        }
        TableColumns.Cell cell = layout.cell(column);
        float tx = isRight ? x + cell.x() + cell.width() - ui.width(font, label) : x + cell.x();
        ui.textCentredY(draw, font, tx, y, h, ImGuiTheme.COL_FG2, label);
    }

    private float rows(List<ConnectionRow> rows, Layout layout, float x, float width, float y) {
        float h = ui.fonts().body().getFontSize() * ROW_EM;
        ImDrawList draw = ImGui.getWindowDrawList();
        float cy = y;
        for (ConnectionRow row : rows) {
            if (selection.isSelected(row) && selection.takeReveal()) {
                ImGui.setCursorScreenPos(x, cy + h * 0.5f);
                ImGui.setScrollHereY(0.5f);
            }
            if (ImGui.isRectVisible(x, cy, x + width, cy + h)) {
                ImGui.pushID("conn-row-" + row.id());
                boolean isClicked = painter.paint(row, layout, x, x + width, cy, h, selection.isSelected(row));
                ImGui.popID();
                if (isClicked) {
                    selection.select(row);
                }
            }
            cy += h;
            draw.addLine(x, cy - ui.m().hairline(), x + width, cy - ui.m().hairline(), ImGuiTheme.COL_BORDER,
                    ui.m().hairline());
        }
        return cy;
    }

    /** Lines in cells are this tall: the two-line account cell stacks two of them. */
    static float cellLine(ImFont font) {
        return font.getFontSize() * CELL_LINE;
    }

    // ── Legend ─────────────────────────────────────────────────────────────

    /** What the console target and the output filter do right now. Returns the y under it. */
    private float legend(ConnectionsView view, float x, float y, float width) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float h = m.controlSmallHeight();
        float lx = x + m.u(1);
        String targetTail = " · where console commands run"
                + view.consoleTarget().map(row -> ": " + row.label()).orElse("");
        float r = ui.fonts().caption().getFontSize() * 0.5f;
        w.radioShape(draw, lx + r, y + h * 0.5f, r, true, false, 1f);
        float after = span(draw, lx + r * 2f + m.u(2), y, h, "Console target", targetTail);
        String filterTail = view.outputFilter()
                .map(row -> " · console only shows " + row.label())
                .orElse(" · off, console shows every connection");
        float filterW = filterSpanWidth(filterTail, view.outputFilter().isPresent());
        float fx = after + m.u(5);
        float fy = y;
        if (fx + filterW > x + width) {
            fx = lx;
            fy = y + h;
        }
        filterSpan(draw, view, fx, fy, h, filterTail);
        return fy + h;
    }

    private float span(ImDrawList draw, float x, float y, float h, String lead, String tail) {
        ImFont bold = ui.fonts().captionMedium();
        ImFont plain = ui.fonts().caption();
        ui.textCentredY(draw, bold, x, y, h, ImGuiTheme.COL_FG, lead);
        float tx = x + ui.width(bold, lead);
        ui.textCentredY(draw, plain, tx, y, h, ImGuiTheme.COL_FG2, tail);
        return tx + ui.width(plain, tail);
    }

    private float filterSpanWidth(String tail, boolean hasShowAll) {
        ImGuiTheme.Metrics m = ui.m();
        float wide = ui.width(ui.fonts().caption(), Icons.FILTER) + m.u(2)
                + ui.width(ui.fonts().captionMedium(), "Output filter") + ui.width(ui.fonts().caption(), tail);
        return hasShowAll ? wide + m.u(2) + w.textButtonWidth("Show all") : wide;
    }

    private void filterSpan(ImDrawList draw, ConnectionsView view, float x, float y, float h, String tail) {
        ImGuiTheme.Metrics m = ui.m();
        String icon = Icons.FILTER;
        ui.textCentredY(draw, ui.fonts().caption(), x, y, h, ImGuiTheme.COL_INFO, icon);
        float end = span(draw, x + ui.width(ui.fonts().caption(), icon) + m.u(2), y, h, "Output filter", tail);
        if (view.outputFilter().isPresent()) {
            ImGui.setCursorScreenPos(end + m.u(2), y);
            if (w.textButton("##conn-show-all", "Show all", h)) {
                model.clearOutputFilter();
            }
        }
    }

    // ── Nothing matches ────────────────────────────────────────────────────

    private boolean nothingMatches(float x, float y, float width) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont font = ui.fonts().small();
        String text = "No connections match.";
        float textY = y + m.u(6);
        ui.text(draw, font, x + (width - ui.width(font, text)) * 0.5f, textY, ImGuiTheme.COL_FG2, text);
        float bw = ui.buttonWidth(null, CLEAR, Tone.GHOST);
        float by = textY + font.getFontSize() * ConnectionWidgets.LINE_HEIGHT + m.u(3);
        ImGui.setCursorScreenPos(x + (width - bw) * 0.5f, by);
        boolean clicked = ui.button("##conn-clear", null, CLEAR, Tone.GHOST, true, m.controlSmallHeight());
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, by + m.controlSmallHeight() + m.u(6) - y);
        return clicked;
    }
}
