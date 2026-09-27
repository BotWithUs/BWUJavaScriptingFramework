package com.botwithus.bot.cli.gui.pages.store;

/** The Store's four views of the catalogue, each shown with a count. */
public enum StoreTab {

    ALL("All subscribed", "All subscribed"),
    INSTALLED("Installed", "Installed"),
    UPDATES("Updates", "Updates available"),
    NOT_INSTALLED("Not installed", "Not installed");

    private final String label;
    private final String heading;

    StoreTab(String label, String heading) {
        this.label = label;
        this.heading = heading;
    }

    /** The segment's label. */
    public String label() {
        return label;
    }

    /** The title over the list while this view is shown. */
    public String heading() {
        return heading;
    }

    /** Whether a script in {@code state} belongs in this view. */
    public boolean admits(RowState state) {
        return switch (this) {
            case ALL -> true;
            case INSTALLED -> state.isOnThisPc();
            case UPDATES -> state.hasUpdate();
            case NOT_INSTALLED -> !state.isOnThisPc();
        };
    }
}
