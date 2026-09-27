package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Motion;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.List;

/**
 * The Store's main list: a titled, bordered box with a caption row, then one
 * {@link StoreRowPainter} row per script the query leaves. While loading it shows
 * breathing placeholder rows; with nothing to show it says why.
 */
final class StoreList {

    private static final int SKELETON_ROWS = 6;
    private static final float SKELETON_BAR = 0.4f;
    private static final float SKELETON_BAR_PX = 10f;
    private static final float SKELETON_DIM = 0.55f;
    private static final double SKELETON_HZ = 1.0 / ImGuiTheme.PULSE_PERIOD_S;
    private static final float LINE_HEIGHT = 1.45f;
    private static final String CLEAR = "Clear filters";

    private final StoreWidgets w;
    private final Controls ui;
    private final StoreRowPainter painter;
    /** Set during {@link #render} when the nothing-matches state's button is clicked. */
    private boolean clearClicked;

    StoreList(StoreWidgets widgets, StoreRowPainter painter) {
        this.w = widgets;
        this.ui = widgets.ui();
        this.painter = painter;
    }

    /**
     * The section at the cursor, full width.
     *
     * @return true when "Clear filters" was clicked in the nothing-matches state
     */
    boolean render(StoreView view, StoreActions actions, boolean isLoading) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float width = ImGui.getContentRegionAvailX();
        ImDrawList draw = ImGui.getWindowDrawList();
        String count = isLoading ? "" : view.listed().size() + " of " + view.all().size();
        float top = w.sectionHeader(draw, x, y, view.query().tab().heading(), count, false) + ui.m().u(2);
        StoreRowPainter.Columns columns = painter.columns(x, width);
        float bodyTop = top + painter.headerHeight();
        clearClicked = false;
        // Rows draw on the upper channel so the box, sized once they are known, goes under them.
        draw.channelsSplit(2);
        draw.channelsSetCurrent(1);
        painter.header(draw, columns, top);
        float bodyH = body(view, actions, isLoading, columns, x, width, bodyTop);
        draw.channelsSetCurrent(0);
        frame(draw, x, top, width, bodyTop - top + bodyH);
        draw.channelsMerge();
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, bodyTop + bodyH - y);
        return clearClicked;
    }

    private float body(StoreView view, StoreActions actions, boolean isLoading, StoreRowPainter.Columns columns,
                       float x, float width, float y) {
        if (isLoading) {
            return skeleton(x, width, y);
        }
        if (view.all().isEmpty()) {
            message(x, width, y, "No scripts on your account yet",
                    "Scripts you buy or subscribe to on the BotWithUs site show up here, ready to install.", false);
            return messageHeight(true, false);
        }
        if (view.listed().isEmpty()) {
            clearClicked = message(x, width, y, "Nothing matches these filters.", "", true);
            return messageHeight(false, true);
        }
        return rows(view, actions, columns, x, width, y);
    }

    private void frame(ImDrawList draw, float x, float y, float width, float h) {
        float r = ui.m().radiusLarge();
        draw.addRectFilled(x, y, x + width, y + h, ImGuiTheme.COL_SURFACE, r);
        draw.addRect(x + 0.5f, y + 0.5f, x + width - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER, r);
    }

    private float rows(StoreView view, StoreActions actions, StoreRowPainter.Columns columns, float x, float width,
                       float y) {
        List<StoreRow> listed = view.listed();
        float h = painter.rowHeight();
        String selected = actions.detailId().orElse("");
        ImDrawList draw = ImGui.getWindowDrawList();
        for (int i = 0; i < listed.size(); i++) {
            float ry = y + i * h;
            draw.addLine(x, ry, x + width, ry, ImGuiTheme.COL_BORDER, ui.m().hairline());
            if (!ImGui.isRectVisible(x, ry, x + width, ry + h)) {
                continue;
            }
            StoreRow row = listed.get(i);
            ImGui.pushID("row-" + row.id());
            StoreRowPainter.Click click = painter.row(row, columns, x + 1f, x + width - 1f, ry,
                    row.id().equals(selected), actions.selection().isPicked(row.id()), view.canInstall());
            ImGui.popID();
            handle(click, row, actions);
        }
        return listed.size() * h;
    }

    private static void handle(StoreRowPainter.Click click, StoreRow row, StoreActions actions) {
        switch (click) {
            case NONE -> { }
            case PICK -> actions.tick(row);
            case STAR -> actions.star(row.id());
            case ACTION -> actions.act(row);
            case ROW -> actions.showDetail(row.id());
        }
    }

    // ── Placeholders and messages ──────────────────────────────────────────

    private float skeleton(float x, float width, float y) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float h = painter.rowHeight();
        float icon = ui.fonts().body().getFontSize() * 2f;
        float alpha = 1f - SKELETON_DIM * Motion.pulse(SKELETON_HZ);
        int fill = Controls.scaleAlpha(ImGuiTheme.COL_ELEVATED, alpha);
        for (int i = 0; i < SKELETON_ROWS; i++) {
            float ry = y + i * h;
            draw.addLine(x, ry, x + width, ry, ImGuiTheme.COL_BORDER, m.hairline());
            float iy = ry + (h - icon) * 0.5f;
            draw.addRectFilled(x + m.u(4), iy, x + m.u(4) + icon, iy + icon, fill, m.radius());
            float bx = x + m.u(4) + icon + m.u(3);
            float by = ry + (h - SKELETON_BAR_PX) * 0.5f;
            draw.addRectFilled(bx, by, bx + (width - bx + x) * SKELETON_BAR, by + SKELETON_BAR_PX, fill,
                    m.radiusSmall());
        }
        return SKELETON_ROWS * h;
    }

    private float messageHeight(boolean hasBody, boolean hasButton) {
        ImGuiTheme.Metrics m = ui.m();
        float h = m.u(6) * 2f + ui.fonts().smallMedium().getFontSize() * LINE_HEIGHT;
        if (hasBody) {
            h += ui.fonts().small().getFontSize() * LINE_HEIGHT * 2f;
        }
        return hasButton ? h + m.u(3) + m.controlSmallHeight() : h;
    }

    /**
     * A centred message inside the box, with a Clear filters button when asked.
     * Returns whether that button was clicked.
     */
    private boolean message(float x, float width, float y, String headline, String body, boolean hasButton) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float cx = x + width * 0.5f;
        ImFont head = ui.fonts().smallMedium();
        float ty = y + m.u(6);
        ui.text(draw, head, cx - ui.width(head, headline) * 0.5f, ty, ImGuiTheme.COL_FG2, headline);
        ty += head.getFontSize() * LINE_HEIGHT;
        if (!body.isEmpty()) {
            ImFont font = ui.fonts().small();
            ty += ui.centredParagraph(draw, font, cx, ty, width - m.u(6) * 2f, font.getFontSize() * LINE_HEIGHT,
                    ImGuiTheme.COL_FG3, body);
        }
        if (!hasButton) {
            return false;
        }
        float bw = ui.buttonWidth(null, CLEAR, Tone.GHOST);
        ImGui.setCursorScreenPos(cx - bw * 0.5f, ty + m.u(3));
        return ui.button("##store-clear-none", null, CLEAR, Tone.GHOST, true, m.controlSmallHeight());
    }
}
