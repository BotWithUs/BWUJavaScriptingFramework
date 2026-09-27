package com.botwithus.bot.cli.gui.pages.management;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;

import imgui.ImDrawList;
import imgui.ImGui;

import java.util.Optional;

/**
 * The scrolling results column: "Scripts" with its list in a card, then the
 * "Failed to load" section. Draws from the cursor down and leaves the cursor
 * below what it drew, so the enclosing child scrolls over it.
 */
final class ManagementList {

    private static final String TITLE = "Scripts";
    private static final String NONE_LOADED = "No management scripts loaded.";

    private final ManagementWidgets w;
    private final ManagementRowPainter rows;
    private final ManagementFailedLoads failures;

    ManagementList(ManagementWidgets widgets) {
        this.w = widgets;
        this.rows = new ManagementRowPainter(widgets);
        this.failures = new ManagementFailedLoads(widgets);
    }

    /** Draws the list and the failures, {@code width} wide, from the cursor. */
    void render(ManagementView view, ManagementState state, float width) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float bottom = y + section(view, x, y);
        bottom += renderCard(view, state, x, bottom, width);
        if (!view.problems().isEmpty()) {
            bottom += w.m().u(4);
            bottom += failures.render(view, state, x, bottom, width);
        }
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, bottom - y + w.m().u(5));
    }

    /** "Scripts 3"; returns its height with the gap under it. */
    private float section(ManagementView view, float x, float y) {
        Controls ui = w.ui();
        ImDrawList draw = ImGui.getWindowDrawList();
        ui.text(draw, ui.fonts().smallMedium(), x, y, ImGuiTheme.COL_FG, TITLE);
        float countX = x + ui.width(ui.fonts().smallMedium(), TITLE) + w.m().u(2);
        ui.text(draw, ui.fonts().monoCaption(), countX, y + w.m().hairline(), ImGuiTheme.COL_FG2,
                Integer.toString(view.scripts().size()));
        return ui.fonts().smallMedium().getFontSize() + w.m().u(2);
    }

    private float renderCard(ManagementView view, ManagementState state, float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        int count = view.scripts().size();
        float bodyH = rows.rowHeight() * Math.max(1, count);
        float h = rows.headHeight() + bodyH;
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + h, ImGuiTheme.COL_SURFACE, m.radiusLarge());
        ManagementRowPainter.Columns c = rows.columns(x, width);
        rows.head(draw, c, y);
        float ry = y + rows.headHeight();
        if (count == 0) {
            draw.addLine(x, ry, x + width, ry, ImGuiTheme.COL_BORDER, m.hairline());
            Controls ui = w.ui();
            ui.textCentredY(draw, ui.fonts().small(), x + m.u(4), ry, rows.rowHeight(), ImGuiTheme.COL_FG2,
                    NONE_LOADED);
        }
        Optional<ManagementRow> selected = state.selected(view);
        for (ManagementRow row : view.scripts()) {
            draw.addLine(x, ry, x + width, ry, ImGuiTheme.COL_BORDER, m.hairline());
            boolean isSelected = selected.map(s -> s.name().equals(row.name())).orElse(false);
            renderRow(row, state, isSelected, c, x, ry, width);
            ry += rows.rowHeight();
        }
        draw.addRect(x + 0.5f, y + 0.5f, x + width - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER, m.radiusLarge());
        return h;
    }

    private void renderRow(ManagementRow row, ManagementState state, boolean isSelected,
                           ManagementRowPainter.Columns c, float x, float y, float width) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float h = rows.rowHeight();
        boolean isHovered = ImGui.isWindowHovered() && ImGui.isMouseHoveringRect(x, y, x + width, y + h);
        if (isSelected || isHovered) {
            draw.addRectFilled(x + 1f, y, x + width - 1f, y + h,
                    isSelected ? ManagementWidgets.COL_ROW_SELECTED : ManagementWidgets.COL_ROW_HOVER);
        }
        rows.paint(row, state, c, y);
        if (isHovered && ImGui.isMouseClicked(0) && !ImGui.isAnyItemHovered()) {
            state.select(row.name());
        }
    }
}
