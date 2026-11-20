package com.botwithus.bot.api.hud;

/**
 * A side window the ribbon opens. Each carries the game's own window number, the key of the
 * cache enum {@code RIBBON_WINDOWS} (ribbon slot to window), and the ribbon slot it had on
 * 951-4, used when the cache cannot be read.
 */
public enum HudTab {

    SKILLS(0, 8),
    BACKPACK(2, 9),
    EQUIPMENT(3, 10),
    PRAYER(4, 11),
    MAGIC(5, 12);

    private final int window;
    private final int fallbackSlot;

    HudTab(int window, int fallbackSlot) {
        this.window = window;
        this.fallbackSlot = fallbackSlot;
    }

    /** The game's window number, as {@code RIBBON_WINDOWS} outputs it. */
    public int window() {
        return window;
    }

    /** The ribbon slot to use when {@code RIBBON_WINDOWS} cannot be read. */
    public int fallbackSlot() {
        return fallbackSlot;
    }
}
