package com.botwithus.bot.cli.gui.pages.connections;

/**
 * The dev preview's reach into the Connections page: selecting a row, a Show
 * choice and a search, which a real user does by clicking and typing. Lives in the preview source
 * set only; nothing here ships.
 */
public final class ConnectionsPreviewSeams {

    private ConnectionsPreviewSeams() {}

    /** Selects the row with {@code id}, opening its detail pane. */
    public static void select(ConnectionsPage page, String id) {
        page.select(id);
    }

    public static void showFilter(ConnectionsPage page, RowFilter filter) {
        page.showFilter(filter);
    }

    /** Types {@code text} into the search box. */
    public static void search(ConnectionsPage page, String text) {
        page.search(text);
    }
}
