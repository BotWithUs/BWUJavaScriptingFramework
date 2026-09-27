package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.nav.NavBadge;
import com.botwithus.bot.cli.gui.nav.Page;
import com.botwithus.bot.cli.gui.nav.PageId;

import imgui.ImGui;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Connections: one table with one lifecycle per pipe. Connected and
 * reconnecting clients first, then pipes a scan found that the host is not
 * connected to, then closed clients waiting for their account to come back.
 * The header holds Auto-connect, the pipe prefix, Scan now and "Connect N
 * found"; the console target and output filter are set from the rows, and a
 * legend under the table says what they do now. Selecting a row opens its
 * detail pane.
 */
public final class ConnectionsPage implements Page {

    /** At or above this page width the detail pane is always shown; below it, only for a selected row. */
    private static final float WIDE_PAGE_EM = 70f;
    private static final float DETAIL_EM = 22.933f;
    private static final float NARROW_DETAIL_EM = 20f;
    private static final int PAGE_COLOR_COUNT = 4;

    private final ConnectionsModel model;
    private final Controls ui;
    private final RowSelection selection = new RowSelection();
    private final ConnectionsHeader header;
    private final ConnectionsFilterBar filters;
    private final ConnectionsTable table;
    private final ConnectionsDetailPane detail;
    private final ConnectionsEmpty empty;

    /** @param navigate switches the Advanced page: the detail pane's scripts and groups lead there */
    public ConnectionsPage(Controls ui, ConnectionsModel model, Consumer<PageId> navigate) {
        this.model = model;
        this.ui = ui;
        ConnectionWidgets widgets = new ConnectionWidgets(ui);
        this.header = new ConnectionsHeader(widgets, model);
        this.filters = new ConnectionsFilterBar(widgets);
        this.table = new ConnectionsTable(widgets, model, selection);
        this.detail = new ConnectionsDetailPane(widgets, model, navigate, selection);
        this.empty = new ConnectionsEmpty(widgets);
    }

    @Override
    public PageId id() {
        return PageId.CONNECTIONS;
    }

    @Override
    public Optional<NavBadge> badge() {
        int n = model.notResponding();
        return n > 0 ? Optional.of(NavBadge.problems(n)) : Optional.empty();
    }

    /** Package-private: the dev preview's seams, standing in for clicks it cannot make. */
    void select(String id) {
        model.view().find(id).ifPresent(selection::selectAndReveal);
    }

    void showFilter(RowFilter filter) {
        filters.show(filter);
    }

    @Override
    public void render() {
        pushPageColors();
        ConnectionsView view = model.view();
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            selection.clear();
        }
        header.render(view);
        float top = ImGui.getWindowPosY() + header.height();
        float x = ImGui.getWindowPosX();
        float width = ImGui.getWindowWidth();
        if (view.rows().isEmpty()) {
            empty.render(view, x, top, width, ImGui.getWindowPosY() + ImGui.getWindowHeight() - top);
        } else {
            ImGuiTheme.Metrics m = ui.m();
            filters.render(view, x + m.u(5), top, width - m.u(5) * 2f);
            renderSplit(view, top + filters.height());
        }
        ImGui.popStyleColor(PAGE_COLOR_COUNT);
        ImGui.popStyleVar();
    }

    /** A quiet scrollbar that only shows its thumb, as on the other pages. */
    private void pushPageColors() {
        Controls.pushColor(ImGuiCol.ScrollbarBg, 0);
        Controls.pushColor(ImGuiCol.ScrollbarGrab, ImGuiTheme.COL_BORDER);
        Controls.pushColor(ImGuiCol.ScrollbarGrabHovered, ImGuiTheme.COL_BORDER_HOVER);
        Controls.pushColor(ImGuiCol.ScrollbarGrabActive, ImGuiTheme.COL_BORDER_HOVER);
        ImGui.pushStyleVar(ImGuiStyleVar.ScrollbarSize, ui.m().u(2));
    }

    /** The scrolling table and, beside it, the detail pane when it is shown. */
    private void renderSplit(ConnectionsView view, float top) {
        float width = ImGui.getWindowWidth();
        float height = ImGui.getWindowPosY() + ImGui.getWindowHeight() - top;
        Optional<ConnectionRow> picked = selection.resolve(view);
        float em = ui.fonts().body().getFontSize();
        boolean isWide = width >= em * WIDE_PAGE_EM;
        boolean showDetail = isWide || picked.isPresent();
        float detailW = showDetail ? em * (isWide ? DETAIL_EM : NARROW_DETAIL_EM) : 0f;
        float x = ImGui.getWindowPosX();
        ImGui.setCursorScreenPos(x, top);
        renderResults(view, width - detailW, height);
        if (showDetail) {
            ImGui.setCursorScreenPos(x + width - detailW, top);
            renderDetail(view, picked, detailW, height, !isWide);
        }
    }

    private void renderResults(ConnectionsView view, float w, float h) {
        ImGuiTheme.Metrics m = ui.m();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, m.u(5), m.u(4));
        ImGui.beginChild("##conn-results", w, h, ImGuiChildFlags.AlwaysUseWindowPadding, 0);
        ImGui.popStyleVar();
        List<ConnectionRow> shown = view.shown(filters.filter(), filters.query());
        if (table.render(view, shown)) {
            filters.clear();
        }
        ImGui.dummy(0f, m.u(4));
        ImGui.endChild();
    }

    private void renderDetail(ConnectionsView view, Optional<ConnectionRow> picked, float w, float h,
                              boolean isClosable) {
        ImGuiTheme.Metrics m = ui.m();
        Controls.pushColor(ImGuiCol.ChildBg, ImGuiTheme.COL_SURFACE);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, m.u(4), m.u(4));
        ImGui.beginChild("##conn-detail", w, h, ImGuiChildFlags.AlwaysUseWindowPadding, 0);
        ImGui.popStyleVar();
        ImGui.popStyleColor();
        float lx = ImGui.getWindowPosX() + m.hairline() * 0.5f;
        ImGui.getWindowDrawList().addLine(lx, ImGui.getWindowPosY(), lx, ImGui.getWindowPosY() + h,
                ImGuiTheme.COL_BORDER, m.hairline());
        if (picked.isPresent()) {
            detail.render(model.detail(picked.get()), view.isAutoConnect(), isClosable);
        } else {
            detail.renderEmpty();
        }
        ImGui.endChild();
    }
}
