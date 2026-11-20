package com.botwithus.bot.api.gameui;

/** What happened to a combo-field or list entry, as the client numbers it. */
public enum SelectionChange {

    DESELECTED(0),
    SELECTED(1),
    /** The list already holds as many selected entries as its limit allows. */
    ERROR_MAX_SELECTED(2),
    /** The entry cannot be selected. */
    ERROR_NOT_SELECTABLE(3),
    /** A number this host does not know. The raw number is still on the event. */
    UNKNOWN(-1);

    private final int code;

    SelectionChange(int code) {
        this.code = code;
    }

    /** The client's number; {@code -1} for {@link #UNKNOWN}. */
    public int code() {
        return code;
    }

    /** The constant for a client number; {@link #UNKNOWN} for anything else. */
    public static SelectionChange fromCode(int code) {
        for (SelectionChange change : values()) {
            if (change != UNKNOWN && change.code == code) {
                return change;
            }
        }
        return UNKNOWN;
    }
}
