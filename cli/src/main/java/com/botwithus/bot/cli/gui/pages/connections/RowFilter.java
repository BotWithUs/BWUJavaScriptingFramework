package com.botwithus.bot.cli.gui.pages.connections;

/** The table's "Show" choice, over the rows the search leaves. */
public enum RowFilter {

    ALL("All"),
    CONNECTED("Connected"),
    FOUND("Found"),
    /** Reconnecting, stopped or closed. */
    PROBLEMS("Problems");

    private final String label;

    RowFilter(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Whether {@code row} is shown under this choice. */
    public boolean matches(ConnectionRow row) {
        return switch (this) {
            case ALL -> true;
            case CONNECTED -> row.group() == RowGroup.CONNECTED;
            case FOUND -> row.group() == RowGroup.FOUND;
            case PROBLEMS -> row.isProblem();
        };
    }
}
