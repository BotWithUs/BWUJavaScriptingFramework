package com.botwithus.bot.cli.gui.pages.store;

/**
 * The dev preview's reach into the Store page's package-private state: a query,
 * the script in the detail pane and batch ticks, which a real user sets by
 * clicking. Lives in the preview source set only; nothing here ships.
 */
public final class StorePreviewSeams {

    private StorePreviewSeams() {}

    public static void showQuery(StorePage page, StoreQuery query) {
        page.showQuery(query);
    }

    public static void showDetail(StorePage page, String id) {
        page.showDetail(id);
    }

    /** Opens the category filter's list, as clicking its button would. */
    public static void openCategoryList(StorePage page) {
        page.openCategoryList();
    }

    /** Ticks {@code ids} for a batch install, as their tick boxes would. */
    public static void tick(StorePage page, String... ids) {
        for (String id : ids) {
            page.tick(id);
        }
    }
}
