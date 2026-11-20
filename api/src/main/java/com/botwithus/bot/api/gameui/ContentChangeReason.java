package com.botwithus.bot.api.gameui;

/** Why an input field's {@link UiHook#ON_CONTENT_CHANGED} was raised, as the client numbers it. */
public enum ContentChangeReason {

    /** The text changed: typed input, or the script's own {@code content} set. */
    CONTENT_CHANGE(0),
    /**
     * The client reports an error. The protocol documents this for a set it truncated
     * or filtered, but on 951-4 a truncated set was measured reporting
     * {@link #CONTENT_CHANGE}; read the field back rather than relying on either.
     */
    ERROR(1),
    SUBMIT(2),
    SUBMIT_ON_ESCAPE(3),
    SUBMIT_ON_FOCUS_LOSS(4),
    /** A number this host does not know. The raw number is still on the event. */
    UNKNOWN(-1);

    private final int code;

    ContentChangeReason(int code) {
        this.code = code;
    }

    /** The client's number; {@code -1} for {@link #UNKNOWN}. */
    public int code() {
        return code;
    }

    /** The constant for a client number; {@link #UNKNOWN} for anything else. */
    public static ContentChangeReason fromCode(int code) {
        for (ContentChangeReason reason : values()) {
            if (reason != UNKNOWN && reason.code == code) {
                return reason;
            }
        }
        return UNKNOWN;
    }
}
