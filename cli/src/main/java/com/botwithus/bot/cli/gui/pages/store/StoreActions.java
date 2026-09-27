package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.cli.gui.nav.PageId;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * What a click on the Store page does, and the page state those clicks change:
 * the batch ticks and which script the detail pane shows. Render thread only.
 */
final class StoreActions {

    private final StoreModel model;
    private final StoreSelection selection = new StoreSelection();
    private final Consumer<PageId> navigate;
    private String detailId;

    /** @param navigate switches the Advanced page, for "Open" and the Installed scripts links */
    StoreActions(StoreModel model, Consumer<PageId> navigate) {
        this.model = model;
        this.navigate = navigate;
    }

    StoreSelection selection() {
        return selection;
    }

    /** The script the detail pane shows, if one was picked. */
    Optional<String> detailId() {
        return Optional.ofNullable(detailId);
    }

    void showDetail(String id) {
        detailId = id;
    }

    void closeDetail() {
        detailId = null;
    }

    void star(String id) {
        model.toggleFavourite(id);
    }

    void tick(StoreRow row) {
        selection.toggle(row);
    }

    /** The row's own button. */
    void act(StoreRow row) {
        switch (RowPresentation.action(row)) {
            case OPEN -> navigate.accept(PageId.INSTALLED);
            case UPDATE, INSTALL -> model.install(List.of(row.id()));
            case CANNOT_INSTALL, INSTALLING -> { }
        }
    }

    void installTicked() {
        model.install(selection.ids());
        selection.clear();
    }

    void openInstalledScripts() {
        navigate.accept(PageId.INSTALLED);
    }

    void refresh() {
        model.refresh();
    }

    void dismissInstallMessage() {
        model.dismissInstallMessage();
    }
}
