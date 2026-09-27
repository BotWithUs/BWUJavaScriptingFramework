package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.cli.gui.CategoryStyle;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Segment;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.Motion;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiSelectableFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.Arrays;
import java.util.List;

/**
 * The Store's two filter rows: the views with their counts, search and sort on
 * the first; price, category, "Runs here", "Made by you" and Clear filters on
 * the second. It reads the query it is given and returns the one the user left.
 */
final class StoreFilterBar {

    private static final int SEARCH_CAPACITY = 96;
    private static final float SEARCH_MIN_EM = 12f;
    private static final float SEARCH_MAX_EM = 24f;
    private static final float SORT_EM = 10.4f;
    private static final float CATEGORY_POPUP_EM = 14f;
    private static final float CATEGORY_POPUP_ROWS = 11.5f;
    private static final String CATEGORY_POPUP = "##store-categories";
    private static final float HOVER_SPEED = 1f / ImGuiTheme.DURATION_FAST_S;
    private static final List<StoreSort> SORTS = List.of(StoreSort.values());
    private static final List<String> SORT_LABELS = SORTS.stream().map(StoreSort::label).toList();

    private final StoreWidgets w;
    private final Controls ui;
    private final ImString search = new ImString(SEARCH_CAPACITY);
    private final ImInt sort = new ImInt();
    /** Opens the category list on the next frame, as clicking its button would. */
    private boolean isCategoryListRequested;

    StoreFilterBar(StoreWidgets widgets) {
        this.w = widgets;
        this.ui = widgets.ui();
    }

    /** Opens the category list on the next frame. Package-private: the dev preview's seam. */
    void requestCategoryList() {
        isCategoryListRequested = true;
    }

    /** Both rows and the gap under them, excluding the bottom border. */
    float height() {
        ImGuiTheme.Metrics m = ui.m();
        return m.controlHeight() * 2f + m.u(3) * 2f;
    }

    /** Draws both rows with their top-left at (x, y), {@code width} wide; returns the query as left. */
    StoreQuery render(StoreQuery query, StoreView view, float x, float y, float width) {
        ImGuiTheme.Metrics m = ui.m();
        if (!search.get().equals(query.search())) {
            search.set(query.search());
        }
        StoreQuery next = firstRow(query, view.counts(), x, y, width);
        next = secondRow(next, view, x, y + m.controlHeight() + m.u(3));
        float lineY = y + height() - m.hairline();
        ImGui.getWindowDrawList().addLine(ImGui.getWindowPosX(), lineY, ImGui.getWindowPosX() + ImGui.getWindowWidth(),
                lineY, ImGuiTheme.COL_BORDER, m.hairline());
        return next;
    }

    // ── Row one: views, search, sort ───────────────────────────────────────

    private StoreQuery firstRow(StoreQuery query, StoreCounts counts, float x, float y, float width) {
        ImGuiTheme.Metrics m = ui.m();
        List<Segment> tabs = Arrays.stream(StoreTab.values())
                .map(t -> new Segment(t.label(), Integer.toString(counts.of(t)), false)).toList();
        float segH = m.controlSmallHeight();
        ImGui.setCursorScreenPos(x, y + (m.controlHeight() - segH) * 0.5f);
        int clicked = ui.segmented("##store-tabs", tabs, query.tab().ordinal(), 0f, segH);
        StoreQuery next = clicked >= 0 ? query.withTab(StoreTab.values()[clicked]) : query;
        float sortW = ui.fonts().body().getFontSize() * SORT_EM;
        float fs = ui.fonts().body().getFontSize();
        float room = width - ui.segmentedWidth(tabs) - sortW - m.u(2) * 2f;
        float searchW = Math.max(fs * SEARCH_MIN_EM, Math.min(fs * SEARCH_MAX_EM, room));
        float right = x + width;
        ImGui.setCursorScreenPos(right - sortW - m.u(2) - searchW, y);
        if (ui.searchBox("##store-search", search, "Search name, author or description", searchW,
                m.controlHeight(), false)) {
            next = next.withSearch(search.get());
        }
        sort.set(next.sort().ordinal());
        ImGui.setCursorScreenPos(right - sortW, y);
        if (ui.select("##store-sort", sort, SORT_LABELS, sortW)) {
            next = next.withSort(SORTS.get(sort.get()));
        }
        return next;
    }

    // ── Row two: price, category, show, clear ──────────────────────────────

    private StoreQuery secondRow(StoreQuery query, StoreView view, float x, float y) {
        ImGuiTheme.Metrics m = ui.m();
        float h = m.controlHeight();
        float cx = label(x, y, h, "Price");
        StoreCounts c = view.counts();
        List<Segment> prices = List.of(Segment.of(PriceFilter.ANY.label()),
                new Segment(PriceFilter.FREE.label(), Integer.toString(c.free()), false),
                new Segment(PriceFilter.PAID.label(), Integer.toString(c.paid()), false));
        float segH = m.controlSmallHeight();
        ImGui.setCursorScreenPos(cx, y + (h - segH) * 0.5f);
        int clicked = ui.segmented("##store-price", prices, query.price().ordinal(), 0f, segH);
        StoreQuery next = clicked >= 0 ? query.withPrice(PriceFilter.values()[clicked]) : query;
        cx = label(cx + ui.segmentedWidth(prices) + m.u(3), y, h, "Category");
        cx = categoryButton(next, view.categories(), cx, y);
        next = categoryPopup(next, view);
        cx = label(cx + m.u(3), y, h, "Show");
        ImGui.setCursorScreenPos(cx, y);
        if (w.checkChip("##store-here", "Runs here", next.runsHereOnly())) {
            next = next.withRunsHereOnly(!next.runsHereOnly());
        }
        cx += w.checkChipWidth("Runs here") + m.u(2);
        ImGui.setCursorScreenPos(cx, y);
        if (w.checkChip("##store-mine", "Made by you", next.madeByYouOnly())) {
            next = next.withMadeByYouOnly(!next.madeByYouOnly());
        }
        cx += w.checkChipWidth("Made by you") + m.u(2);
        if (next.hasActiveFilters()) {
            ImGui.setCursorScreenPos(cx, y + (h - segH) * 0.5f);
            if (w.link("##store-clear", null, "Clear filters", true, segH)) {
                search.set("");
                next = next.cleared();
            }
        }
        return next;
    }

    /** A caption label before a control; returns the x where the control starts. */
    private float label(float x, float y, float h, String text) {
        ui.textCentredY(ImGui.getWindowDrawList(), ui.fonts().caption(), x, y, h, ImGuiTheme.COL_FG2, text);
        return x + ui.width(ui.fonts().caption(), text) + ui.m().u(2);
    }

    private static String categoryLabel(StoreQuery query) {
        return switch (query.categories().size()) {
            case 0 -> "Any";
            case 1 -> query.categories().iterator().next().getDisplayName();
            default -> query.categories().size() + " categories";
        };
    }

    /** A select-styled button that opens the category list. Returns the x after it. */
    private float categoryButton(StoreQuery query, List<ScriptCategory> present, float x, float y) {
        ImGuiTheme.Metrics m = ui.m();
        float h = m.controlHeight();
        String label = categoryLabel(query);
        float chevron = ui.width(ui.fonts().caption(), Icons.ANGLE_DOWN);
        float bw = m.u(3) * 2f + ui.width(ui.fonts().small(), label) + m.u(2) + chevron;
        ImGui.setCursorScreenPos(x, y);
        ImGui.beginDisabled(present.isEmpty());
        boolean clicked = ImGui.invisibleButton("##store-category", bw, h);
        if (clicked || isCategoryListRequested) {
            isCategoryListRequested = false;
            ImGui.openPopup(CATEGORY_POPUP);
            ImGui.setNextWindowPos(x, y + h + m.u(1));
        }
        float t = Motion.step("store-cat", ImGui.isItemHovered() ? 1f : 0f, HOVER_SPEED);
        ImGui.endDisabled();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + bw, y + h, ImGuiTheme.COL_BG, m.radius());
        draw.addRect(x + 0.5f, y + 0.5f, x + bw - 0.5f, y + h - 0.5f,
                Controls.lerp(ImGuiTheme.COL_BORDER, ImGuiTheme.COL_BORDER_HOVER, t), m.radius());
        int fg = query.categories().isEmpty() ? ImGuiTheme.COL_FG2 : ImGuiTheme.COL_FG;
        ui.textCentredY(draw, ui.fonts().small(), x + m.u(3), y, h, fg, label);
        ui.textCentredY(draw, ui.fonts().caption(), x + bw - m.u(3) - chevron, y, h, ImGuiTheme.COL_FG2,
                Icons.ANGLE_DOWN);
        return x + bw;
    }

    /** The category list, while open: one row per category in the catalogue, with its count. */
    private StoreQuery categoryPopup(StoreQuery query, StoreView view) {
        ImGuiTheme.Metrics m = ui.m();
        Controls.pushColor(ImGuiCol.PopupBg, ImGuiTheme.COL_ELEVATED);
        Controls.pushColor(ImGuiCol.Border, ImGuiTheme.COL_BORDER);
        Controls.pushColor(ImGuiCol.Header, ImGuiTheme.COL_SURFACE);
        Controls.pushColor(ImGuiCol.HeaderHovered, ImGuiTheme.COL_BORDER);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, m.u(1), m.u(1));
        ImGui.pushStyleVar(ImGuiStyleVar.PopupRounding, m.radius());
        StoreQuery next = query;
        // Tall enough for most catalogues; a longer list scrolls rather than leaving the window.
        float rowH = m.controlSmallHeight() + ImGui.getStyle().getItemSpacingY();
        ImGui.setNextWindowSizeConstraints(0f, 0f, Float.MAX_VALUE, rowH * CATEGORY_POPUP_ROWS + m.u(1) * 2f);
        if (ImGui.beginPopup(CATEGORY_POPUP)) {
            ImGui.pushFont(ui.fonts().small());
            for (ScriptCategory category : view.categories()) {
                next = categoryRow(next, view, category);
            }
            ImGui.popFont();
            ImGui.endPopup();
        }
        ImGui.popStyleVar(2);
        ImGui.popStyleColor(4);
        return next;
    }

    private StoreQuery categoryRow(StoreQuery query, StoreView view, ScriptCategory category) {
        ImGuiTheme.Metrics m = ui.m();
        float rowW = ui.fonts().body().getFontSize() * CATEGORY_POPUP_EM;
        float h = m.controlSmallHeight();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        boolean isOn = query.categories().contains(category);
        boolean clicked = ImGui.selectable("##cat-" + category.name(), false, ImGuiSelectableFlags.DontClosePopups,
                rowW, h);
        ImDrawList draw = ImGui.getWindowDrawList();
        if (isOn) {
            ui.textCentredY(draw, ui.fonts().caption(), x + m.u(2), y, h, ImGuiTheme.COL_ACCENT, Icons.CHECK);
        }
        float ix = x + m.u(2) + m.u(4);
        ui.textCentredY(draw, ui.fonts().caption(), ix, y, h, CategoryStyle.color(category, 1f),
                CategoryStyle.icon(category));
        ui.textCentredY(draw, ui.fonts().small(), ix + m.u(5), y, h, isOn ? ImGuiTheme.COL_FG : ImGuiTheme.COL_FG2,
                category.getDisplayName());
        String count = Long.toString(view.all().stream().filter(r -> r.category() == category).count());
        ui.textCentredY(draw, ui.fonts().monoCaption(), x + rowW - m.u(2) - ui.width(ui.fonts().monoCaption(), count),
                y, h, ImGuiTheme.COL_FG3, count);
        return clicked ? query.withCategoryToggled(category) : query;
    }
}
