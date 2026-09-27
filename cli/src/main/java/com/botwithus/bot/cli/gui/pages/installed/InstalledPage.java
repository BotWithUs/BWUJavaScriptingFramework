package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.nav.NavBadge;
import com.botwithus.bot.cli.gui.nav.Page;
import com.botwithus.bot.cli.gui.nav.PageId;
import com.botwithus.bot.cli.gui.nav.SecondLine;
import com.botwithus.bot.cli.gui.pages.installed.InstalledHeaderBar.Action;
import com.botwithus.bot.cli.gui.pages.installed.InstalledState.DetailTab;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Installed scripts: the local library, every script loaded from the scripts
 * folder, and where each one runs. A script is a file on disk and each client
 * that runs it has its own runner, so a row answers "where does this run?":
 * one dot per client, Start on… and Stop everywhere. Under the list, the JARs
 * that failed to load; beside it, the selected script's Clients and About tabs.
 *
 * <p>The page draws; the {@link InstalledModel} reads the host and does the work.</p>
 */
public final class InstalledPage implements Page {

    /** Below this page width the detail pane is left out and the list takes the room. */
    private static final float WIDE_PAGE_EM = 55f;
    private static final float DETAIL_EM = 22.667f;
    private static final float EMPTY_ICON_EM = 3.733f;
    private static final float EMPTY_TEXT_EM = 30.667f;
    private static final float EMPTY_LINE = 1.5f;
    private static final int PAGE_COLORS = 4;

    private final InstalledModel model;
    private final Controls ui;
    private final SecondLine.FolderPath folder;
    private final InstalledState state;
    private final InstalledWidgets widgets;
    private final InstalledHeaderBar header;
    private final InstalledList list;
    private final InstalledDetail detail;
    private final StartOnDialog startOn;

    /**
     * @param folder   the scripts folder as the sidebar's second line shows it
     * @param navigate switches the Advanced page: Get more, Install again and Update go to the Store
     */
    public InstalledPage(Controls ui, InstalledModel model, SecondLine.FolderPath folder,
                         Consumer<PageId> navigate) {
        this.model = model;
        this.ui = ui;
        this.folder = folder;
        this.state = new InstalledState(model, navigate);
        this.widgets = new InstalledWidgets(ui);
        this.header = new InstalledHeaderBar(widgets);
        this.list = new InstalledList(widgets);
        this.detail = new InstalledDetail(widgets);
        this.startOn = new StartOnDialog(widgets);
    }

    @Override
    public PageId id() {
        return PageId.INSTALLED;
    }

    @Override
    public Optional<SecondLine> secondLine() {
        return Optional.of(folder);
    }

    /** Load problems plus scripts that stalled or crashed; none when all is well. */
    @Override
    public Optional<NavBadge> badge() {
        int n = model.view().attentionCount();
        return n > 0 ? Optional.of(NavBadge.problems(n)) : Optional.empty();
    }

    // Package-private: the dev preview's seams, standing in for clicks it cannot make.

    void select(String key, DetailTab tab) {
        state.select(key);
        state.showTab(tab);
    }

    void showQuery(InstalledQuery query) {
        state.setQuery(query);
    }

    void openStartOn(String key) {
        state.openStartOn(key);
    }

    void tick(String clientId) {
        state.toggleTick(clientId);
    }

    void openTrace(Path jar) {
        state.toggleTrace(jar);
    }

    void armStop(String key) {
        state.armStop(key);
    }

    @Override
    public void render() {
        pushPageStyle();
        InstalledView view = model.view();
        float x = ImGui.getWindowPosX();
        float y = ImGui.getWindowPosY();
        float width = ImGui.getWindowWidth();
        float pad = ui.m().u(5);
        run(header.renderHeader(view, x + pad, y, width - pad * 2f));
        float top = y + header.headerHeight(view, width - pad * 2f);
        if (view.isEmpty()) {
            emptyState(view, x, top, width, y + ImGui.getWindowHeight() - top);
        } else {
            state.setQuery(header.renderFilters(view, state.query(), x + pad, top, width - pad * 2f, x, x + width));
            top += header.filterHeight();
            split(view, x, top, width, y + ImGui.getWindowHeight() - top);
        }
        startOn.render(view, state);
        popPageStyle();
    }

    private void run(Action action) {
        switch (action) {
            case NONE -> { }
            case WATCH_ON -> model.setWatching(true);
            case WATCH_OFF -> model.setWatching(false);
            case RESTART_ON -> model.setRestartAfterReload(true);
            case RESTART_OFF -> model.setRestartAfterReload(false);
            case OPEN_FOLDER -> model.openFolder();
            case RELOAD -> model.reload();
            case GET_MORE -> state.openStore();
        }
    }

    /** The results, scrolling, and the detail pane beside them when there is room. */
    private void split(InstalledView view, float x, float y, float width, float height) {
        List<InstalledScript> shown = state.query().apply(view.scripts());
        Optional<InstalledScript> selected = state.selected(view, shown);
        boolean hasDetail = width >= widgets.fs() * WIDE_PAGE_EM && selected.isPresent();
        float detailW = hasDetail ? widgets.fs() * DETAIL_EM : 0f;
        ImGuiTheme.Metrics m = ui.m();
        ImGui.setCursorScreenPos(x, y);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, m.u(5), m.u(4));
        ImGui.beginChild("##installed-results", width - detailW, height, ImGuiChildFlags.AlwaysUseWindowPadding, 0);
        ImGui.popStyleVar();
        list.render(view, shown, state, ImGui.getContentRegionAvailX());
        ImGui.endChild();
        if (hasDetail) {
            ImGui.setCursorScreenPos(x + width - detailW, y);
            detail.render(view, selected.get(), state, detailW, height);
        }
    }

    /** No script and no failed JAR: point at the Store and the folder. */
    private void emptyState(InstalledView view, float x, float y, float width, float height) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        String title = "No scripts in " + view.header().folderLabel() + " yet";
        String body = "Install scripts from your account in the Script Store, or drop a script JAR "
                + "into the folder and reload.";
        List<String> lines = ui.wrap(ui.fonts().small(), body, widgets.fs() * EMPTY_TEXT_EM);
        float icon = widgets.fs() * EMPTY_ICON_EM;
        float lh = ui.fonts().small().getFontSize() * EMPTY_LINE;
        float blockH = icon + m.u(3) * 3f + ui.fonts().bodyMedium().getFontSize() + lh * lines.size()
                + m.controlHeight();
        float cx = x + width * 0.5f;
        float cy = y + Math.max(m.u(6), (height - blockH) * 0.5f);
        draw.addCircle(cx, cy + icon * 0.5f, icon * 0.5f, ImGuiTheme.COL_BORDER, 0, m.hairline());
        ui.text(draw, ui.fonts().titleMedium(), cx - ui.width(ui.fonts().titleMedium(), Icons.FOLDER_OPEN) * 0.5f,
                cy + (icon - ui.fonts().titleMedium().getFontSize()) * 0.5f, ImGuiTheme.COL_FG2, Icons.FOLDER_OPEN);
        cy += icon + m.u(3);
        ui.text(draw, ui.fonts().bodyMedium(), cx - ui.width(ui.fonts().bodyMedium(), title) * 0.5f, cy,
                ImGuiTheme.COL_FG, title);
        cy += ui.fonts().bodyMedium().getFontSize() + m.u(3);
        cy += ui.centredParagraph(draw, ui.fonts().small(), cx, cy, widgets.fs() * EMPTY_TEXT_EM, lh,
                ImGuiTheme.COL_FG2, body) + m.u(3);
        emptyButtons(cx, cy);
    }

    private void emptyButtons(float cx, float y) {
        float storeW = ui.buttonWidth(Icons.BAG_SHOPPING, "Open Script Store", Tone.PRIMARY);
        float folderW = ui.buttonWidth(Icons.FOLDER_OPEN, "Open folder", Tone.GHOST);
        ImGui.setCursorScreenPos(cx - (storeW + ui.m().u(2) + folderW) * 0.5f, y);
        if (ui.button("##empty-store", Icons.BAG_SHOPPING, "Open Script Store", Tone.PRIMARY, true)) {
            state.openStore();
        }
        ImGui.sameLine(0f, ui.m().u(2));
        if (ui.button("##empty-folder", Icons.FOLDER_OPEN, "Open folder", Tone.GHOST, true)) {
            model.openFolder();
        }
    }

    /** A quiet scrollbar that only shows its thumb, as on the Clients page, and no item spacing. */
    private void pushPageStyle() {
        Controls.pushColor(ImGuiCol.ScrollbarBg, 0);
        Controls.pushColor(ImGuiCol.ScrollbarGrab, ImGuiTheme.COL_BORDER);
        Controls.pushColor(ImGuiCol.ScrollbarGrabHovered, ImGuiTheme.COL_BORDER_HOVER);
        Controls.pushColor(ImGuiCol.ScrollbarGrabActive, ImGuiTheme.COL_BORDER_HOVER);
        ImGui.pushStyleVar(ImGuiStyleVar.ScrollbarSize, ui.m().u(2));
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0f, 0f);
    }

    private static void popPageStyle() {
        ImGui.popStyleColor(PAGE_COLORS);
        ImGui.popStyleVar(2);
    }
}
