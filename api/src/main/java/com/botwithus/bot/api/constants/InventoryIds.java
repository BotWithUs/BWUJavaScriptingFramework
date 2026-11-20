package com.botwithus.bot.api.constants;

public final class InventoryIds {
    private InventoryIds() {}

    public static final int BACKPACK = 93;
    public static final int EQUIPMENT = 94;
    public static final int BANK = 95;
    /**
     * The summoned familiar's beast-of-burden inventory ({@code LORE_BOB} in the gameval
     * index). This constant held 671 until it was checked against the index, which names
     * 671 {@code WORN_DISPLAY_PREVIEW}; nothing read the old value.
     */
    public static final int FAMILIAR = 530;
}
