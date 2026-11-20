package com.botwithus.bot.api.hud;

import com.botwithus.bot.api.gameval.GamevalRef;
import com.botwithus.bot.api.gameval.GamevalType;

/**
 * A side window the ribbon opens, with the ribbon option that opens it and the window's own
 * interface (gameval name, with the id it had when this was written as the fallback).
 */
public enum HudTab {

    BACKPACK("Open Backpack", "TOPLEVEL_V2_INVENTORY", 1473),
    EQUIPMENT("Open Worn Equipment", "TOPLEVEL_V2_WORN", 1464),
    SKILLS("Open Skills", "STATS_CHILD", 1466),
    PRAYER("Open Prayers", "TOPLEVEL_V2_PRAYER", 1458),
    MAGIC("Open Magic Abilities", "TOPLEVEL_V2_WINDOW_ABILITY_BOOK_MAGIC", 1461);

    private final String option;
    private final GamevalRef window;

    HudTab(String option, String windowName, int windowId) {
        this.option = option;
        this.window = new GamevalRef(GamevalType.INTERFACE, windowName, windowId);
    }

    /** The ribbon option that opens the window. */
    public String option() {
        return option;
    }

    /** The window's interface. */
    public GamevalRef window() {
        return window;
    }
}
