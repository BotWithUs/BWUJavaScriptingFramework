package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.pages.installed.ScriptRowPainter.Columns;

import imgui.ImDrawList;
import imgui.ImGui;

import java.util.List;
import java.util.Optional;

/**
 * The scrolling results column: the list of scripts in its card, then the
 * "Failed to load" section. Draws from the cursor down and leaves the cursor
 * below what it drew, so the enclosing child scrolls over it.
 */
final class InstalledList {

    private static final String NOTHING_MATCHES = "Nothing matches.";

    private final InstalledWidgets w;
    private final ScriptRowPainter rows;
    private final FailedLoadsPainter failures;

    InstalledList(InstalledWidgets widgets) {
        this.w = widgets;
        this.rows = new ScriptRowPainter(widgets);
        this.failures = new FailedLoadsPainter(widgets);
    }

    /** Draws the list of {@code shown} and the failures, {@code width} wide, from the cursor. */
    void render(InstalledView view, List<InstalledScript> shown, InstalledState state, float width) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float listH = renderCard(view, shown, state, x, y, width);
        float bottom = y + listH;
        if (!view.problems().isEmpty()) {
            bottom += w.m().u(4);
            bottom += failures.render(view, state, x, bottom, width);
        }
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, bottom - y + w.m().u(5));
    }

    private float renderCard(InstalledView view, List<InstalledScript> shown, InstalledState state,
                             float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        float bodyH = shown.isEmpty() ? rows.rowHeight() : rows.rowHeight() * shown.size();
        float h = rows.headHeight() + bodyH;
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + h, ImGuiTheme.COL_SURFACE, m.radiusLarge());
        Columns c = rows.columns(x, width);
        rows.head(draw, c, y);
        float ry = y + rows.headHeight();
        if (shown.isEmpty()) {
            nothingMatches(view, state, x, ry, width);
        }
        Optional<InstalledScript> selected = state.selected(view, shown);
        for (InstalledScript s : shown) {
            draw.addLine(x, ry, x + width, ry, ImGuiTheme.COL_BORDER, m.hairline());
            renderRow(s, state, state.isSelected(s, selected), c, x, ry, width);
            ry += rows.rowHeight();
        }
        draw.addRect(x + 0.5f, y + 0.5f, x + width - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER, m.radiusLarge());
        return h;
    }

    private void renderRow(InstalledScript s, InstalledState state, boolean isSelected, Columns c,
                           float x, float y, float width) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float h = rows.rowHeight();
        boolean isHovered = ImGui.isWindowHovered() && ImGui.isMouseHoveringRect(x, y, x + width, y + h);
        if (isSelected || isHovered) {
            draw.addRectFilled(x + 1f, y, x + width - 1f, y + h,
                    isSelected ? InstalledWidgets.COL_ROW_SELECTED : InstalledWidgets.COL_ROW_HOVER);
        }
        rows.paint(draw, s, state, c, y);
        rows.actions(s, state, c, y);
        if (isHovered && ImGui.isMouseClicked(0) && !ImGui.isAnyItemHovered()) {
            state.select(s.key());
        }
    }

    private void nothingMatches(InstalledView view, InstalledState state, float x, float y, float width) {
        Controls ui = w.ui();
        float h = rows.rowHeight();
        float textW = ui.width(ui.fonts().small(), NOTHING_MATCHES);
        float linkW = w.linkWidth(null, "Clear filters");
        float left = x + (width - textW - w.m().u(1) - linkW) * 0.5f;
        String text = view.scripts().isEmpty() ? "No scripts loaded." : NOTHING_MATCHES;
        ui.textCentredY(ImGui.getWindowDrawList(), ui.fonts().small(), left, y, h, ImGuiTheme.COL_FG2, text);
        if (view.scripts().isEmpty()) {
            return;
        }
        ImGui.setCursorScreenPos(left + textW + w.m().u(1), y + (h - w.m().controlSmallHeight()) * 0.5f);
        if (w.link("##clear-filters", null, "Clear filters", w.m().controlSmallHeight())) {
            state.setQuery(InstalledQuery.DEFAULT);
        }
    }
}
