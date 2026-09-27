package com.botwithus.bot.cli.gui.pages.management;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.nav.NavBadge;
import com.botwithus.bot.cli.gui.nav.Page;
import com.botwithus.bot.cli.gui.nav.PageId;
import com.botwithus.bot.cli.gui.nav.SecondLine;
import com.botwithus.bot.cli.gui.pages.management.ManagementState.DetailTab;
import com.botwithus.bot.cli.management.Target;

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
 * Management: the host-level scripts from the management folder, which run
 * once for the host rather than per client, and what each one applies to.
 * A list of scripts with their targets, state and loop time, the JARs that
 * failed to load under it, and beside it the selected script's Overview,
 * Settings, Script UI and Activity.
 *
 * <p>The page draws; the {@link ManagementModel} reads the host and does the work.</p>
 */
public final class ManagementPage implements Page {

    /** Below this page width the detail pane is left out and the list takes the room. */
    private static final float WIDE_PAGE_EM = 50f;
    private static final float DETAIL_EM = 22.667f;
    private static final float EMPTY_ICON_EM = 3.733f;
    private static final float EMPTY_TEXT_EM = 30.667f;
    private static final float EMPTY_LINE = 1.5f;
    private static final int PAGE_COLORS = 4;
    private static final String EMPTY_BODY = "Drop a JAR that implements ManagementScript into %s, then"
            + " reload. It can then run breaks, restarts or schedules across your clients.";

    private final ManagementModel model;
    private final Controls ui;
    private final SecondLine.FolderPath folder;
    private final ManagementState state;
    private final ManagementWidgets widgets;
    private final ManagementHeaderBar header;
    private final ManagementList list;
    private final ManagementDetail detail;

    /**
     * @param folder   the management folder as the sidebar's second line shows it
     * @param navigate switches the Advanced page: a group chip goes to Groups
     */
    public ManagementPage(Controls ui, ManagementModel model, SecondLine.FolderPath folder,
                          Consumer<PageId> navigate) {
        this.model = model;
        this.ui = ui;
        this.folder = folder;
        this.state = new ManagementState(model, navigate);
        this.widgets = new ManagementWidgets(ui);
        this.header = new ManagementHeaderBar(widgets);
        this.list = new ManagementList(widgets);
        this.detail = new ManagementDetail(widgets);
    }

    @Override
    public PageId id() {
        return PageId.MANAGEMENT;
    }

    @Override
    public Optional<SecondLine> secondLine() {
        return Optional.of(folder);
    }

    /** JARs that failed to load plus scripts whose last run crashed; none when all is well. */
    @Override
    public Optional<NavBadge> badge() {
        int n = model.view().attentionCount();
        return n > 0 ? Optional.of(NavBadge.problems(n)) : Optional.empty();
    }

    /**
     * Selects {@code script} on its Overview, as a robot link elsewhere in the
     * app does before it switches here. Render thread.
     */
    public void show(String script) {
        state.select(script);
        state.showTab(DetailTab.OVERVIEW);
    }

    // Package-private: the dev preview's seams, standing in for clicks it cannot make.

    void select(String script, DetailTab tab) {
        state.select(script);
        state.showTab(tab);
    }

    void armStopAll() {
        state.armStopAll();
    }

    void openTrace(Path jar) {
        state.toggleTrace(jar);
    }

    void pickAddKind(String script, int kindOrdinal, int pick) {
        state.select(script);
        state.setAddKind(TargetKind.values()[kindOrdinal]);
        state.setAddPick(pick);
    }

    void requestAdd(Target target, String label) {
        state.selected(model.view()).ifPresent(row ->
                state.requestChange(row, new TargetChange.Add(target), label));
    }

    @Override
    public void render() {
        pushPageStyle();
        ManagementView view = model.view();
        float x = ImGui.getWindowPosX();
        float y = ImGui.getWindowPosY();
        float width = ImGui.getWindowWidth();
        float pad = ui.m().u(5);
        header.render(view, state, x + pad, y, width - pad * 2f, x, x + width);
        float top = y + header.height(width - pad * 2f);
        float height = y + ImGui.getWindowHeight() - top;
        if (view.isEmpty()) {
            emptyState(view, x, top, width, height);
        } else {
            split(view, x, top, width, height);
        }
        popPageStyle();
    }

    /** The results, scrolling, and the detail pane beside them when there is room. */
    private void split(ManagementView view, float x, float y, float width, float height) {
        Optional<ManagementRow> selected = state.selected(view);
        boolean hasDetail = width >= widgets.fs() * WIDE_PAGE_EM && selected.isPresent();
        float detailW = hasDetail ? widgets.fs() * DETAIL_EM : 0f;
        ImGuiTheme.Metrics m = ui.m();
        ImGui.setCursorScreenPos(x, y);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, m.u(5), m.u(4));
        ImGui.beginChild("##mgmt-results", width - detailW, height, ImGuiChildFlags.AlwaysUseWindowPadding, 0);
        ImGui.popStyleVar();
        list.render(view, state, ImGui.getContentRegionAvailX());
        ImGui.endChild();
        if (hasDetail) {
            ImGui.setCursorScreenPos(x + width - detailW, y);
            detail.render(view, selected.get(), state, detailW, height);
        }
    }

    /** No script and no failed JAR: explain what goes in the folder. */
    private void emptyState(ManagementView view, float x, float y, float width, float height) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        String title = view.isReloading() ? "Loading management scripts…" : "No management scripts yet";
        String body = String.format(EMPTY_BODY, view.folderLabel());
        float textW = widgets.fs() * EMPTY_TEXT_EM;
        List<String> lines = ui.wrap(ui.fonts().small(), body, textW);
        float icon = widgets.fs() * EMPTY_ICON_EM;
        float lh = ui.fonts().small().getFontSize() * EMPTY_LINE;
        float blockH = icon + m.u(3) * 3f + ui.fonts().bodyMedium().getFontSize() + lh * lines.size()
                + m.controlHeight();
        float cx = x + width * 0.5f;
        float cy = y + Math.max(m.u(6), (height - blockH) * 0.5f);
        draw.addCircle(cx, cy + icon * 0.5f, icon * 0.5f, ImGuiTheme.COL_BORDER, 0, m.hairline());
        ui.text(draw, ui.fonts().titleMedium(), cx - ui.width(ui.fonts().titleMedium(), Icons.ROBOT) * 0.5f,
                cy + (icon - ui.fonts().titleMedium().getFontSize()) * 0.5f, ImGuiTheme.COL_FG2, Icons.ROBOT);
        cy += icon + m.u(3);
        ui.text(draw, ui.fonts().bodyMedium(), cx - ui.width(ui.fonts().bodyMedium(), title) * 0.5f, cy,
                ImGuiTheme.COL_FG, title);
        cy += ui.fonts().bodyMedium().getFontSize() + m.u(3);
        cy += ui.centredParagraph(draw, ui.fonts().small(), cx, cy, textW, lh, ImGuiTheme.COL_FG2, body) + m.u(3);
        emptyButtons(view, cx, cy);
    }

    private void emptyButtons(ManagementView view, float cx, float y) {
        float folderW = ui.buttonWidth(Icons.FOLDER_OPEN, "Open folder", Tone.GHOST);
        float reloadW = ui.buttonWidth(Icons.ROTATE, "Reload", Tone.GHOST);
        ImGui.setCursorScreenPos(cx - (folderW + ui.m().u(2) + reloadW) * 0.5f, y);
        if (ui.button("##mgmt-empty-folder", Icons.FOLDER_OPEN, "Open folder", Tone.GHOST, true)) {
            model.openFolder();
        }
        ImGui.sameLine(0f, ui.m().u(2));
        if (ui.button("##mgmt-empty-reload", Icons.ROTATE, "Reload", Tone.GHOST, !view.isReloading())) {
            model.reload();
        }
    }

    /** A quiet scrollbar that only shows its thumb, as on the other pages, and no item spacing. */
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
