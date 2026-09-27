package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.nav.Page;
import com.botwithus.bot.cli.gui.nav.PageId;
import com.botwithus.bot.cli.gui.nav.SecondLine;

import imgui.ImGui;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * The Script Store: every script the signed-in account owns or subscribes to,
 * with where each stands on this PC. Views with counts, price / category / "runs
 * here" / "made by you" filters, search and sort; a favourites row the filters
 * never hide; a list with a tick box per row for batch installs; a detail pane;
 * and a floating "Install N scripts" bar holding the page's only primary button.
 * When the launcher has no catalogue to give, the page says why instead.
 *
 * <p>The page is the Store's front only. Installed scripts are started, stopped
 * and configured from Installed scripts and the client cards, which "Open" leads to.</p>
 */
public final class StorePage implements Page {

    /** Below this page width the detail pane opens only when a script is picked. */
    private static final float WIDE_PAGE_EM = 70f;
    private static final float DETAIL_EM = 22.667f;
    /** Room under the list so its last row can scroll clear of the install bar. */
    private static final float BAR_CLEARANCE_EM = 6.4f;
    private static final int PAGE_COLOR_COUNT = 4;

    private final StoreModel model;
    private final Controls ui;
    private final StoreActions actions;
    private final StoreHeader header;
    private final StoreFilterBar filters;
    private final StoreBanners banners;
    private final FavouriteTiles favourites;
    private final StoreList list;
    private final StoreDetail detail;
    private final StoreNotice notice;
    private final InstallBar bar;
    private StoreQuery query = StoreQuery.DEFAULT;

    /** @param navigate switches the Advanced page; "Open" goes to {@link PageId#INSTALLED} */
    public StorePage(Controls ui, StoreModel model, Consumer<PageId> navigate) {
        this.model = model;
        this.ui = ui;
        this.actions = new StoreActions(model, navigate);
        StoreWidgets widgets = new StoreWidgets(ui);
        StoreRowPainter rows = new StoreRowPainter(widgets);
        this.header = new StoreHeader(widgets);
        this.filters = new StoreFilterBar(widgets);
        this.banners = new StoreBanners(widgets);
        this.favourites = new FavouriteTiles(widgets, rows);
        this.list = new StoreList(widgets, rows);
        this.detail = new StoreDetail(widgets, rows);
        this.notice = new StoreNotice(widgets);
        this.bar = new InstallBar(widgets);
    }

    @Override
    public PageId id() {
        return PageId.STORE;
    }

    @Override
    public Optional<SecondLine> secondLine() {
        return Optional.of(model.signInLine());
    }

    /**
     * Picks the script with catalogue id {@code catalogueId} and opens its detail
     * pane, as Installed scripts' Install again and Update do. The filters stay
     * as they are: the detail pane shows the script whether or not they list it.
     */
    public void show(String catalogueId) {
        actions.showDetail(catalogueId);
    }

    /** Package-private: the dev preview's seams, standing in for clicks it cannot make. */
    void showQuery(StoreQuery next) {
        query = next;
    }

    void showDetail(String id) {
        actions.showDetail(id);
    }

    void openCategoryList() {
        filters.requestCategoryList();
    }

    void tick(String id) {
        model.view(query).find(id).ifPresent(actions::tick);
    }

    @Override
    public void render() {
        pushPageColors();
        StoreView view = model.view(query);
        model.markSeen();
        actions.selection().retainInstallable(view.all());
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            actions.selection().clear();
        }
        switch (view.status()) {
            case StoreStatus.Unavailable u -> {
                header.render(view, actions, false);
                notice.render(u.notice(), actions, ImGui.getWindowPosY() + header.height());
            }
            case StoreStatus.Loading ignored -> renderCatalogue(view);
            case StoreStatus.Ready ignored -> renderCatalogue(view);
        }
        ImGui.popStyleColor(PAGE_COLOR_COUNT);
        ImGui.popStyleVar();
    }

    /** A quiet scrollbar that only shows its thumb, as on the Clients page. */
    private void pushPageColors() {
        Controls.pushColor(ImGuiCol.ScrollbarBg, 0);
        Controls.pushColor(ImGuiCol.ScrollbarGrab, ImGuiTheme.COL_BORDER);
        Controls.pushColor(ImGuiCol.ScrollbarGrabHovered, ImGuiTheme.COL_BORDER_HOVER);
        Controls.pushColor(ImGuiCol.ScrollbarGrabActive, ImGuiTheme.COL_BORDER_HOVER);
        ImGui.pushStyleVar(ImGuiStyleVar.ScrollbarSize, ui.m().u(2));
    }

    private void renderCatalogue(StoreView view) {
        ImGuiTheme.Metrics m = ui.m();
        header.render(view, actions, true);
        float x = ImGui.getWindowPosX();
        float top = ImGui.getWindowPosY() + header.height();
        query = filters.render(query, view, x + m.u(5), top, ImGui.getWindowWidth() - m.u(5) * 2f);
        top += filters.height();
        banners.render(view, actions, x + m.u(5), top, ImGui.getWindowWidth() - m.u(5) * 2f);
        top += banners.height(view);
        renderSplit(view, top);
    }

    /** The scrolling results and, beside them, the detail pane when it is open. */
    private void renderSplit(StoreView view, float top) {
        float width = ImGui.getWindowWidth();
        float height = ImGui.getWindowPosY() + ImGui.getWindowHeight() - top;
        Optional<StoreRow> picked = actions.detailId().flatMap(view::find);
        if (actions.detailId().isPresent() && picked.isEmpty() && view.hasCatalogue()) {
            actions.closeDetail();
        }
        boolean isWide = width >= ui.fonts().body().getFontSize() * WIDE_PAGE_EM;
        boolean showDetail = isWide || picked.isPresent();
        float detailW = showDetail ? ui.fonts().body().getFontSize() * DETAIL_EM : 0f;
        float x = ImGui.getWindowPosX();
        ImGui.setCursorScreenPos(x, top);
        renderResults(view, width - detailW, height);
        bar.render(actions, view.canInstall(), x, x + width - detailW, top + height);
        if (showDetail) {
            ImGui.setCursorScreenPos(x + width - detailW, top);
            renderDetail(view, picked, detailW, height, !isWide);
        }
    }

    private void renderResults(StoreView view, float w, float h) {
        ImGuiTheme.Metrics m = ui.m();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, m.u(5), m.u(4));
        ImGui.beginChild("##store-results", w, h, ImGuiChildFlags.AlwaysUseWindowPadding, 0);
        ImGui.popStyleVar();
        boolean isLoading = !view.hasCatalogue();
        String selected = actions.detailId().orElse("");
        favourites.render(view, actions, isLoading, selected);
        ImGui.dummy(0f, m.u(5));
        if (list.render(view, actions, isLoading)) {
            query = query.cleared();
        }
        ImGui.dummy(0f, ui.fonts().body().getFontSize() * BAR_CLEARANCE_EM);
        ImGui.endChild();
    }

    private void renderDetail(StoreView view, Optional<StoreRow> picked, float w, float h, boolean isClosable) {
        ImGuiTheme.Metrics m = ui.m();
        Controls.pushColor(ImGuiCol.ChildBg, ImGuiTheme.COL_SURFACE);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, m.u(4), m.u(4));
        ImGui.beginChild("##store-detail", w, h, ImGuiChildFlags.AlwaysUseWindowPadding, 0);
        ImGui.popStyleVar();
        ImGui.popStyleColor();
        float lx = ImGui.getWindowPosX() + m.hairline() * 0.5f;
        ImGui.getWindowDrawList().addLine(lx, ImGui.getWindowPosY(), lx, ImGui.getWindowPosY() + h,
                ImGuiTheme.COL_BORDER, m.hairline());
        if (picked.isPresent() && view.hasCatalogue()) {
            detail.render(picked.get(), actions, view.canInstall(), isClosable);
        } else {
            detail.renderEmpty();
        }
        ImGui.endChild();
    }
}
