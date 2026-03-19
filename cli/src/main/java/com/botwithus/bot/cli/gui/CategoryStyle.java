package com.botwithus.bot.cli.gui;

import com.botwithus.bot.api.ScriptCategory;

import java.util.EnumMap;
import java.util.Map;

/**
 * Maps each {@link ScriptCategory} to a Font Awesome icon and a unique accent color
 * for use in the Scripts panel UI.
 */
public final class CategoryStyle {

    /** Icon string + RGB color (0-1 floats) for a category. */
    public record Style(String icon, float r, float g, float b) {}

    private static final Map<ScriptCategory, Style> STYLES = new EnumMap<>(ScriptCategory.class);

    static {
        // Warm earth / gold tones for gathering
        put(ScriptCategory.UNCATEGORIZED,  Icons.QUESTION,    0.55f, 0.58f, 0.65f); // slate grey
        put(ScriptCategory.SKILLING,       Icons.HAMMER,      0.95f, 0.76f, 0.20f); // amber gold
        put(ScriptCategory.COMBAT,         Icons.CROSSHAIRS,  0.97f, 0.33f, 0.33f); // scarlet
        put(ScriptCategory.FISHING,        Icons.FISH,        0.30f, 0.72f, 0.90f); // ocean blue
        put(ScriptCategory.MINING,         Icons.GEM,         0.68f, 0.52f, 0.35f); // bronze
        put(ScriptCategory.WOODCUTTING,    Icons.TREE,        0.34f, 0.75f, 0.40f); // forest green
        put(ScriptCategory.AGILITY,        Icons.RUNNING,     0.40f, 0.85f, 0.95f); // cyan
        put(ScriptCategory.COOKING,        Icons.FIRE,        0.98f, 0.58f, 0.24f); // flame orange
        put(ScriptCategory.CRAFTING,       Icons.SCISSORS,    0.85f, 0.65f, 0.90f); // lavender
        put(ScriptCategory.FARMING,        Icons.SEEDLING,    0.50f, 0.82f, 0.32f); // lime green
        put(ScriptCategory.HERBLORE,       Icons.FLASK,       0.20f, 0.80f, 0.55f); // teal green
        put(ScriptCategory.HUNTER,         Icons.BULLSEYE,    0.80f, 0.50f, 0.20f); // rust
        put(ScriptCategory.PRAYER,         Icons.PRAY,        0.95f, 0.92f, 0.68f); // pale gold
        put(ScriptCategory.RUNECRAFTING,   Icons.HAT_WIZARD,  0.60f, 0.50f, 0.95f); // indigo
        put(ScriptCategory.SLAYER,         Icons.SKULL,       0.75f, 0.20f, 0.20f); // blood red
        put(ScriptCategory.SMITHING,       Icons.GAVEL,       0.72f, 0.72f, 0.72f); // steel
        put(ScriptCategory.THIEVING,       Icons.MASK,        0.55f, 0.30f, 0.70f); // deep purple
        put(ScriptCategory.FLETCHING,      Icons.FEATHER,     0.55f, 0.80f, 0.70f); // sage
        put(ScriptCategory.CONSTRUCTION,   Icons.HOUSE,       0.85f, 0.72f, 0.45f); // sandstone
        put(ScriptCategory.DIVINATION,     Icons.EYE,         0.80f, 0.90f, 0.35f); // chartreuse
        put(ScriptCategory.INVENTION,      Icons.LIGHTBULB,   0.95f, 0.85f, 0.30f); // electric yellow
        put(ScriptCategory.ARCHAEOLOGY,    Icons.BONE,        0.78f, 0.62f, 0.42f); // clay
        put(ScriptCategory.NECROMANCY,     Icons.GHOST,       0.40f, 0.90f, 0.80f); // spectral teal
        put(ScriptCategory.SUMMONING,      Icons.PAW,         0.30f, 0.55f, 0.90f); // cobalt
        put(ScriptCategory.DUNGEONEERING,  Icons.DUNGEON,     0.60f, 0.40f, 0.30f); // dark brown
        put(ScriptCategory.MINIGAME,       Icons.GAMEPAD,     0.95f, 0.45f, 0.70f); // hot pink
        put(ScriptCategory.MONEYMAKING,    Icons.COINS,       0.98f, 0.84f, 0.28f); // gold
        put(ScriptCategory.QUESTING,       Icons.MAP,         0.60f, 0.75f, 0.95f); // periwinkle
        put(ScriptCategory.UTILITY,        Icons.WRENCH,      0.60f, 0.65f, 0.98f); // soft blue
        put(ScriptCategory.OTHER,          Icons.STAR,        0.65f, 0.65f, 0.70f); // cool grey
    }

    private CategoryStyle() {}

    private static void put(ScriptCategory cat, String icon, float r, float g, float b) {
        STYLES.put(cat, new Style(icon, r, g, b));
    }

    /**
     * Returns the style for the given category.
     */
    public static Style of(ScriptCategory category) {
        return STYLES.getOrDefault(category, STYLES.get(ScriptCategory.UNCATEGORIZED));
    }

    /**
     * Returns the icon string for the given category.
     */
    public static String icon(ScriptCategory category) {
        return of(category).icon();
    }

    /**
     * Returns a packed ImGui color int for the given category at the specified alpha.
     */
    public static int color(ScriptCategory category, float alpha) {
        Style s = of(category);
        return ImGuiTheme.imCol32(s.r, s.g, s.b, alpha);
    }
}
