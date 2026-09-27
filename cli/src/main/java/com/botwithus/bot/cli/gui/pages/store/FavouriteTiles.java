package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Motion;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.List;

/**
 * The pinned favourites above the list: one compact tile per starred script, in
 * the order they were starred, whatever the filters say. A tile opens the script
 * in the detail pane; its star unpins it.
 */
final class FavouriteTiles {

    private static final float TILE_MIN_EM = 14.667f;
    private static final float ICON_EM = 2f;
    private static final float LINE_HEIGHT = 1.45f;
    private static final float HOVER_SPEED = 1f / ImGuiTheme.DURATION_FAST_S;
    private static final String EMPTY = "Star the scripts you use most and they stay pinned here, "
            + "whatever filter you pick.";

    private final StoreWidgets w;
    private final Controls ui;
    private final StoreRowPainter rows;

    FavouriteTiles(StoreWidgets widgets, StoreRowPainter rows) {
        this.w = widgets;
        this.ui = widgets.ui();
        this.rows = rows;
    }

    /** The section at the cursor, full width; returns the height it used. */
    float render(StoreView view, StoreActions actions, boolean isLoading, String selectedId) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float width = ImGui.getContentRegionAvailX();
        String count = isLoading ? "" : Integer.toString(view.favourites().size());
        float top = w.sectionHeader(ImGui.getWindowDrawList(), x, y, "Favourites", count, true) + ui.m().u(2);
        float h;
        if (isLoading) {
            h = loadingTile(x, top, tileWidth(width));
        } else if (view.favourites().isEmpty()) {
            h = empty(x, top, width);
        } else {
            h = grid(view.favourites(), actions, x, top, width, selectedId);
        }
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, top - y + h);
        return top - y + h;
    }

    private float tileWidth(float width) {
        float gap = ui.m().u(2);
        float min = ui.fonts().body().getFontSize() * TILE_MIN_EM;
        int columns = Math.max(1, (int) ((width + gap) / (min + gap)));
        return (width - gap * (columns - 1)) / columns;
    }

    private float tileHeight() {
        float lines = ui.fonts().smallMedium().getFontSize() * LINE_HEIGHT
                + ui.fonts().caption().getFontSize() * LINE_HEIGHT;
        return ui.m().u(3) * 2f + Math.max(lines, ui.fonts().body().getFontSize() * ICON_EM);
    }

    private float grid(List<StoreRow> favourites, StoreActions actions, float x, float y, float width,
                       String selectedId) {
        float gap = ui.m().u(2);
        float tileW = tileWidth(width);
        int columns = Math.max(1, Math.round((width + gap) / (tileW + gap)));
        float tileH = tileHeight();
        for (int i = 0; i < favourites.size(); i++) {
            StoreRow row = favourites.get(i);
            float tx = x + (i % columns) * (tileW + gap);
            float ty = y + (float) (i / columns) * (tileH + gap);
            ImGui.pushID("fav-" + row.id());
            tile(row, actions, tx, ty, tileW, tileH, row.id().equals(selectedId));
            ImGui.popID();
        }
        int rowsUsed = (favourites.size() + columns - 1) / columns;
        return rowsUsed * tileH + (rowsUsed - 1) * gap;
    }

    private void tile(StoreRow row, StoreActions actions, float x, float y, float tw, float th, boolean isSelected) {
        ImGuiTheme.Metrics m = ui.m();
        boolean isHovered = ImGui.isWindowHovered() && ImGui.isMouseHoveringRect(x, y, x + tw, y + th);
        float t = Motion.step("fav:" + row.id(), isHovered ? 1f : 0f, HOVER_SPEED);
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + tw, y + th, ImGuiTheme.COL_SURFACE, m.radiusLarge());
        int border = isSelected ? ImGuiTheme.COL_INFO : Controls.lerp(ImGuiTheme.COL_BORDER, ImGuiTheme.COL_BORDER_HOVER, t);
        draw.addRect(x + 0.5f, y + 0.5f, x + tw - 0.5f, y + th - 0.5f, border, m.radiusLarge());
        float icon = ui.fonts().body().getFontSize() * ICON_EM;
        rows.iconTile(draw, row, x + m.u(3), y + (th - icon) * 0.5f, icon, 1f);
        float starX = x + tw - m.u(3) - w.starSize();
        ImGui.setCursorScreenPos(starX, y + (th - w.starSize()) * 0.5f);
        if (w.star("##star", row.isFavourite())) {
            actions.star(row.id());
        }
        float tx = x + m.u(3) + icon + m.u(3);
        float room = starX - m.u(2) - tx;
        ImFont nameFont = ui.fonts().smallMedium();
        float lines = nameFont.getFontSize() * LINE_HEIGHT + ui.fonts().caption().getFontSize() * LINE_HEIGHT;
        float ly = y + (th - lines) * 0.5f;
        ui.textCentredY(draw, nameFont, tx, ly, nameFont.getFontSize() * LINE_HEIGHT, ImGuiTheme.COL_FG,
                ui.ellipsize(nameFont, row.name(), room));
        ui.textCentredY(draw, ui.fonts().caption(), tx, ly + nameFont.getFontSize() * LINE_HEIGHT,
                ui.fonts().caption().getFontSize() * LINE_HEIGHT, ImGuiTheme.COL_FG2,
                ui.ellipsize(ui.fonts().caption(), RowPresentation.tileLine(row), room));
        if (isHovered && ImGui.isMouseClicked(0) && !ImGui.isAnyItemHovered()) {
            actions.showDetail(row.id());
        }
    }

    private float loadingTile(float x, float y, float tw) {
        float th = tileHeight();
        ImDrawList draw = ImGui.getWindowDrawList();
        ImGuiTheme.Metrics m = ui.m();
        draw.addRectFilled(x, y, x + tw, y + th, ImGuiTheme.COL_SURFACE, m.radiusLarge());
        draw.addRect(x + 0.5f, y + 0.5f, x + tw - 0.5f, y + th - 0.5f, ImGuiTheme.COL_BORDER, m.radiusLarge());
        float icon = ui.fonts().body().getFontSize() * ICON_EM;
        draw.addRectFilled(x + m.u(3), y + (th - icon) * 0.5f, x + m.u(3) + icon, y + (th + icon) * 0.5f,
                ImGuiTheme.COL_ELEVATED, m.radius());
        ui.textCentredY(draw, ui.fonts().smallMedium(), x + m.u(3) + icon + m.u(3), y, th, ImGuiTheme.COL_FG3,
                "Loading…");
        return th;
    }

    private float empty(float x, float y, float width) {
        ImGuiTheme.Metrics m = ui.m();
        ImFont font = ui.fonts().small();
        float h = m.u(4) * 2f + font.getFontSize() * LINE_HEIGHT;
        ImDrawList draw = ImGui.getWindowDrawList();
        w.dashedRect(draw, x, y, x + width, y + h, ImGuiTheme.COL_BORDER);
        float r = font.getFontSize() * StoreWidgets.GLYPH_STAR_EM;
        w.starShape(draw, x + m.u(4) + r, y + h * 0.5f, r, ImGuiTheme.COL_FG3, false);
        ui.textCentredY(draw, font, x + m.u(4) + r * 2f + m.u(2), y, h, ImGuiTheme.COL_FG2,
                ui.ellipsize(font, EMPTY, width - m.u(4) * 2f - r * 2f - m.u(2)));
        return h;
    }
}
