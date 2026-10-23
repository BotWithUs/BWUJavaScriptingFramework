package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Segment;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.pages.installed.InstalledQuery.Show;
import com.botwithus.bot.cli.gui.pages.installed.InstalledQuery.SourceFilter;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.type.ImString;

import java.util.List;

/**
 * The top of the page: the title and its meta line, the Watch folder and
 * Restart after reload switches, Open folder, Reload and Get more; then the
 * filter bar under it. The controls wrap onto a second line when the page is
 * too narrow for one.
 */
final class InstalledHeaderBar {

    /** What the header asks for this frame. */
    enum Action { NONE, WATCH_ON, WATCH_OFF, RESTART_ON, RESTART_OFF, OPEN_FOLDER, RELOAD, GET_MORE }

    private static final String TITLE = "Installed scripts";
    private static final String WATCH = "Watch folder";
    private static final String RESTART = "Restart after reload";
    private static final String SEARCH_HINT = "Name, category or JAR";
    private static final int SEARCH_CAPACITY = 128;
    private static final float SEARCH_EM = 16f;
    private static final float SEARCH_MIN_EM = 9f;
    private static final float DIVIDER_EM = 1.333f;
    private static final float SPINNER_EM = 0.367f;
    private static final List<Show> SHOWS = List.of(Show.ALL, Show.RUNNING, Show.NOT_RUNNING, Show.PROBLEMS);
    private static final List<String> SHOW_LABELS = List.of("All", "Running", "Not running", "Problems");
    private static final List<SourceFilter> SOURCES = List.of(SourceFilter.ANY, SourceFilter.STORE,
            SourceFilter.LOCAL);
    private static final List<Segment> SOURCE_SEGMENTS = List.of(Segment.of("Any"), Segment.of("Store"),
            Segment.of("Local build"));

    private final InstalledWidgets w;
    private final ImString search = new ImString(SEARCH_CAPACITY);

    InstalledHeaderBar(InstalledWidgets widgets) {
        this.w = widgets;
    }

    // ── Header ─────────────────────────────────────────────────────────────

    /** The header's height at {@code width}: one row of controls, or two when they wrap. */
    float headerHeight(InstalledView view, float width) {
        ImGuiTheme.Metrics m = w.m();
        float rows = fitsOneLine(view, width) ? 1f : 2f;
        return m.u(4) + m.controlHeight() * rows + m.u(2) * (rows - 1f) + m.u(3);
    }

    /** Draws the header across {@code width} from (x, y); returns what was clicked. */
    Action renderHeader(InstalledView view, float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        Controls ui = w.ui();
        float top = y + m.u(4);
        float h = m.controlHeight();
        ui.textCentredY(draw, ui.fonts().titleMedium(), x, top, h, ImGuiTheme.COL_FG, TITLE);
        float metaX = x + ui.width(ui.fonts().titleMedium(), TITLE) + m.u(3);
        drawMeta(draw, view, metaX, top, h);
        boolean isOneLine = fitsOneLine(view, width);
        float cx = isOneLine ? x + width - controlsWidth() : x;
        float cy = isOneLine ? top : top + h + m.u(2);
        return renderControls(view.header(), cx, cy);
    }

    private void drawMeta(ImDrawList draw, InstalledView view, float x, float y, float h) {
        Controls ui = w.ui();
        if (!view.header().isReloading()) {
            ui.textCentredY(draw, ui.fonts().monoCaption(), x, y, h, ImGuiTheme.COL_FG2, view.meta());
            return;
        }
        float r = w.fs() * SPINNER_EM;
        w.spinner(draw, x + r, y + h * 0.5f, r);
        ui.textCentredY(draw, ui.fonts().monoCaption(), x + r * 2f + w.m().u(1.5f), y, h, ImGuiTheme.COL_FG2,
                "Reloading " + view.header().folderLabel() + "…");
    }

    private boolean fitsOneLine(InstalledView view, float width) {
        Controls ui = w.ui();
        float title = ui.width(ui.fonts().titleMedium(), TITLE) + w.m().u(3);
        float meta = ui.width(ui.fonts().monoCaption(), view.meta()) + w.m().u(4);
        return title + meta + controlsWidth() <= width;
    }

    private float controlsWidth() {
        Controls ui = w.ui();
        ImGuiTheme.Metrics m = w.m();
        return w.switchWidth(WATCH) + w.switchWidth(RESTART) + m.u(3) * 2f + w.fs() * DIVIDER_EM
                + w.linkWidth(Icons.FOLDER_OPEN, "Open folder") + m.u(2) * 2f
                + ui.buttonWidth(Icons.ROTATE, "Reload", Tone.GHOST)
                + ui.buttonWidth(Icons.BAG_SHOPPING, "Get more", Tone.GHOST);
    }

    private Action renderControls(InstalledHeader header, float x, float y) {
        ImGuiTheme.Metrics m = w.m();
        float h = m.controlHeight();
        Action action = Action.NONE;
        ImGui.setCursorScreenPos(x, y);
        if (w.switchLabel("##watch", WATCH, header.isWatching(), h,
                "Reload automatically when a JAR in " + header.folderLabel() + " changes")) {
            action = header.isWatching() ? Action.WATCH_OFF : Action.WATCH_ON;
        }
        ImGui.sameLine(0f, m.u(3));
        if (w.switchLabel("##restart", RESTART, header.isRestartAfterReload(), h,
                "After a reload, start scripts again on the clients that were running them")) {
            action = header.isRestartAfterReload() ? Action.RESTART_OFF : Action.RESTART_ON;
        }
        ImGui.sameLine(0f, 0f);
        float divX = ImGui.getCursorScreenPosX() + w.fs() * DIVIDER_EM * 0.5f + m.u(1.5f);
        ImGui.getWindowDrawList().addLine(divX, y + m.u(1.25f), divX, y + h - m.u(1.25f), ImGuiTheme.COL_BORDER);
        ImGui.sameLine(0f, w.fs() * DIVIDER_EM + m.u(3));
        return renderButtons(header, action);
    }

    private Action renderButtons(InstalledHeader header, Action soFar) {
        ImGuiTheme.Metrics m = w.m();
        Action action = soFar;
        if (w.link("##open-folder", Icons.FOLDER_OPEN, "Open folder", m.controlHeight())) {
            action = Action.OPEN_FOLDER;
        }
        ImGui.sameLine(0f, m.u(2));
        if (w.ui().button("##reload", Icons.ROTATE, "Reload", Tone.GHOST, !header.isReloading())) {
            action = Action.RELOAD;
        }
        ImGui.sameLine(0f, m.u(2));
        if (w.ui().button("##get-more", Icons.BAG_SHOPPING, "Get more", Tone.GHOST, true)) {
            action = Action.GET_MORE;
        }
        return action;
    }

    // ── Filter bar ─────────────────────────────────────────────────────────

    float filterHeight() {
        return w.m().controlHeight() + w.m().u(3);
    }

    /** Draws the filter bar across {@code width} from (x, y), with a rule under it; returns the new query. */
    InstalledQuery renderFilters(InstalledView view, InstalledQuery query, float x, float y, float width,
                                 float ruleX0, float ruleX1) {
        ImGuiTheme.Metrics m = w.m();
        Controls ui = w.ui();
        float segH = m.controlSmallHeight();
        float segY = y + (m.controlHeight() - segH) * 0.5f;
        ImGui.setCursorScreenPos(x, segY);
        InstalledQuery next = showSegments(view, query, segH);
        float afterShow = x + ui.segmentedWidth(showSegmentsOf(view)) + m.u(4);
        ui.textCentredY(ImGui.getWindowDrawList(), ui.fonts().caption(), afterShow, y, m.controlHeight(),
                ImGuiTheme.COL_FG2, "Source");
        float srcX = afterShow + ui.width(ui.fonts().caption(), "Source") + m.u(2);
        ImGui.setCursorScreenPos(srcX, segY);
        next = sourceSegments(next, segH);
        float searchX = srcX + ui.segmentedWidth(SOURCE_SEGMENTS) + m.u(3);
        float searchW = Math.max(w.fs() * SEARCH_MIN_EM, Math.min(w.fs() * SEARCH_EM, x + width - searchX));
        ImGui.setCursorScreenPos(Math.max(searchX, x + width - searchW), y);
        next = searchBox(next, searchW);
        float ruleY = y + filterHeight() - m.hairline();
        ImGui.getWindowDrawList().addLine(ruleX0, ruleY, ruleX1, ruleY, ImGuiTheme.COL_BORDER, m.hairline());
        return next;
    }

    private static List<Segment> showSegmentsOf(InstalledView view) {
        return SHOWS.stream()
                .map(s -> new Segment(SHOW_LABELS.get(SHOWS.indexOf(s)),
                        Integer.toString(InstalledQuery.count(s, view.scripts())), false))
                .toList();
    }

    private InstalledQuery showSegments(InstalledView view, InstalledQuery query, float h) {
        int clicked = w.ui().segmented("##show", showSegmentsOf(view), SHOWS.indexOf(query.show()), 0f, h);
        return clicked >= 0 ? query.withShow(SHOWS.get(clicked)) : query;
    }

    private InstalledQuery sourceSegments(InstalledQuery query, float h) {
        int clicked = w.ui().segmented("##source", SOURCE_SEGMENTS, SOURCES.indexOf(query.source()), 0f, h);
        return clicked >= 0 ? query.withSource(SOURCES.get(clicked)) : query;
    }

    private InstalledQuery searchBox(InstalledQuery query, float width) {
        if (!search.get().equals(query.search())) {
            search.set(query.search());
        }
        boolean changed = w.ui().searchBox("##installed-search", search, SEARCH_HINT, width,
                w.m().controlHeight(), false);
        return changed ? query.withSearch(search.get()) : query;
    }
}
