package com.botwithus.bot.api.gameui;

/** Where {@code ui.order} moves a component among its siblings. */
public enum UiOrder {

    FRONT("front"),
    BACK("back");

    private final String wireName;

    UiOrder(String wireName) {
        this.wireName = wireName;
    }

    /** The value {@code ui.order}'s {@code to} param takes. */
    public String wireName() {
        return wireName;
    }
}
