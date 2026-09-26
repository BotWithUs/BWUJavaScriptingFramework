package com.botwithus.bot.cli.gui.usermode.board;

/** The price badge on a "Your subscriptions" row. */
public enum PriceBadge {

    FREE("Free"),
    PAID("Paid"),
    /** The launcher did not say whether the script is free, so no badge is drawn. */
    NONE("");

    private final String label;

    PriceBadge(String label) {
        this.label = label;
    }

    /**
     * Maps the catalogue's three-valued {@code isFree}: {@code TRUE} is Free,
     * {@code FALSE} is Paid, and {@code null} (not reported) is no badge rather
     * than a guess either way.
     */
    public static PriceBadge of(Boolean isFree) {
        if (isFree == null) {
            return NONE;
        }
        return isFree ? FREE : PAID;
    }

    public String label() {
        return label;
    }
}
