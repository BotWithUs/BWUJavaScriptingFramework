package com.botwithus.bot.api;

/**
 * Categories for classifying {@link BotScript} implementations.
 * Used in {@link ScriptManifest#category()} to group scripts in the UI.
 */
public enum ScriptCategory {

    UNCATEGORIZED("Uncategorized"),
    SKILLING("Skilling"),
    COMBAT("Combat"),
    FISHING("Fishing"),
    MINING("Mining"),
    WOODCUTTING("Woodcutting"),
    AGILITY("Agility"),
    COOKING("Cooking"),
    CRAFTING("Crafting"),
    FARMING("Farming"),
    HERBLORE("Herblore"),
    HUNTER("Hunter"),
    PRAYER("Prayer"),
    RUNECRAFTING("Runecrafting"),
    SLAYER("Slayer"),
    SMITHING("Smithing"),
    THIEVING("Thieving"),
    FLETCHING("Fletching"),
    CONSTRUCTION("Construction"),
    DIVINATION("Divination"),
    INVENTION("Invention"),
    ARCHAEOLOGY("Archaeology"),
    NECROMANCY("Necromancy"),
    SUMMONING("Summoning"),
    DUNGEONEERING("Dungeoneering"),
    /**
     * Firemaking training.
     *
     * <p>Added in a later {@code bot-api} release than most categories. A host
     * built before it existed cannot read a manifest that declares it, so a
     * script that does needs a host at least as new as the API it compiled against.
     */
    FIREMAKING("Firemaking"),
    /**
     * Magic training: alching, enchanting and other spell-based skilling.
     * Making runes stays under {@link #RUNECRAFTING}.
     *
     * <p>Added in a later {@code bot-api} release than most categories, with the
     * same caveat as {@link #FIREMAKING}: a host built before it existed cannot
     * read a manifest that declares it.
     */
    MAGIC("Magic"),
    MINIGAME("Minigame"),
    MONEYMAKING("Moneymaking"),
    QUESTING("Questing"),
    UTILITY("Utility"),
    OTHER("Other");

    private final String displayName;

    ScriptCategory(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the human-readable display name for this category.
     *
     * @return the display name
     */
    public String getDisplayName() {
        return displayName;
    }
}
