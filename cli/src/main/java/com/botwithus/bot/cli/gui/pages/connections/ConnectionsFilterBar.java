package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Segment;
import com.botwithus.bot.cli.gui.ImGuiTheme;

import imgui.ImGui;
import imgui.type.ImString;

import java.util.List;

/**
 * The bar under the header: the Show choice with a count on each (All,
 * Connected, Found, Problems) and a search over account, pipe and UUID.
 */
final class ConnectionsFilterBar {

    private static final float SEARCH_EM = 16f;
    private static final int SEARCH_CAPACITY = 128;
    private static final List<RowFilter> FILTERS = List.of(RowFilter.values());

    private final Controls ui;
    private final ImString search = new ImString(SEARCH_CAPACITY);
    private RowFilter filter = RowFilter.ALL;

    ConnectionsFilterBar(ConnectionWidgets widgets) {
        this.ui = widgets.ui();
    }

    RowFilter filter() {
        return filter;
    }

    String query() {
        return search.get();
    }

    void show(RowFilter next) {
        filter = next;
    }

    /** Back to every row, with no search. */
    void clear() {
        filter = RowFilter.ALL;
        search.set("");
    }

    /** The bar's height, its bottom padding included. */
    float height() {
        return ui.m().controlHeight() + ui.m().u(3);
    }

    /** Draws the bar at (x, y) across {@code width}, with a rule under it across the whole window. */
    void render(ConnectionsView view, float x, float y, float width) {
        ImGuiTheme.Metrics m = ui.m();
        List<Segment> segments = FILTERS.stream()
                .map(f -> new Segment(f.label(), Integer.toString(view.count(f)), false))
                .toList();
        ImGui.setCursorScreenPos(x, y);
        int clicked = ui.segmented("##conn-show", segments, FILTERS.indexOf(filter), 0f, m.controlHeight());
        if (clicked >= 0) {
            filter = FILTERS.get(clicked);
        }
        float searchW = Math.min(ui.fonts().body().getFontSize() * SEARCH_EM,
                width - ui.segmentedWidth(segments) - m.u(3));
        if (searchW > m.controlHeight() * 2f) {
            ImGui.setCursorScreenPos(x + width - searchW, y);
            ui.searchBox("##conn-search", search, "Account, pipe or UUID", searchW, m.controlHeight(), false);
        }
        float ruleY = y + height() - m.hairline();
        ImGui.getWindowDrawList().addLine(ImGui.getWindowPosX(), ruleY,
                ImGui.getWindowPosX() + ImGui.getWindowWidth(), ruleY, ImGuiTheme.COL_BORDER, m.hairline());
    }
}
