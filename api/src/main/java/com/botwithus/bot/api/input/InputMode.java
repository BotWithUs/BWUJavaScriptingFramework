package com.botwithus.bot.api.input;

/**
 * What the game's input dialog is asking for, read from the client variable
 * {@code MESLAYERMODE}. Each mode accepts a different set of characters, which
 * is why {@link InputDialog} checks it before typing.
 */
public enum InputMode {
    /** No input dialog is open. */
    CLOSED(0),
    /** A player name ("Enter name of friend to add to list"). */
    NAME(2),
    /** A quantity ("Enter amount:"), e.g. withdraw-X / deposit-X. */
    AMOUNT(7),
    /** A dialog is open in a mode this API has no verified limits for. */
    OTHER(Integer.MIN_VALUE);

    /**
     * What {@code MESLAYERMODE} reads before any dialog has opened this session.
     * Observed live on 950-1 right after login. It is the same value
     * {@code getVarcInt} yields for an unresolvable variable, and it is closed in
     * both cases.
     */
    private static final int NEVER_SET = -1;

    private final int varcValue;

    InputMode(int varcValue) {
        this.varcValue = varcValue;
    }

    /**
     * The mode a {@code MESLAYERMODE} value names. {@code -1}, the value before any
     * dialog has opened, is {@link #CLOSED}. Any other value not listed here is
     * {@link #OTHER}.
     */
    public static InputMode fromVarc(int value) {
        if (value == NEVER_SET) {
            return CLOSED;
        }
        for (InputMode mode : values()) {
            if (mode != OTHER && mode.varcValue == value) {
                return mode;
            }
        }
        return OTHER;
    }
}
