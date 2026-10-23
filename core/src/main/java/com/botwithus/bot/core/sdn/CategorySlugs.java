package com.botwithus.bot.core.sdn;

import com.botwithus.bot.api.ScriptCategory;

import java.util.Locale;
import java.util.Map;

/**
 * Maps the site's category slugs onto the host's {@link ScriptCategory}.
 *
 * <p>The host enum is what the rest of the host displays, because a local script
 * declares its category in its manifest with that enum. The site keeps its own
 * list of slugs, so a Store script is shown under the host category its slug
 * names here. This is the one table that does so.
 */
public final class CategorySlugs {

    private static final Map<String, ScriptCategory> BY_SLUG = Map.ofEntries(
            Map.entry("agility", ScriptCategory.AGILITY),
            Map.entry("combat", ScriptCategory.COMBAT),
            Map.entry("construction", ScriptCategory.CONSTRUCTION),
            Map.entry("cooking", ScriptCategory.COOKING),
            Map.entry("crafting", ScriptCategory.CRAFTING),
            Map.entry("divination", ScriptCategory.DIVINATION),
            Map.entry("farming", ScriptCategory.FARMING),
            Map.entry("firemaking", ScriptCategory.FIREMAKING),
            Map.entry("fishing", ScriptCategory.FISHING),
            Map.entry("fletching", ScriptCategory.FLETCHING),
            Map.entry("herblore", ScriptCategory.HERBLORE),
            Map.entry("hunter", ScriptCategory.HUNTER),
            Map.entry("invention", ScriptCategory.INVENTION),
            Map.entry("magic", ScriptCategory.MAGIC),
            Map.entry("mini_games", ScriptCategory.MINIGAME),
            Map.entry("mining", ScriptCategory.MINING),
            Map.entry("misc", ScriptCategory.OTHER),
            Map.entry("money_making", ScriptCategory.MONEYMAKING),
            Map.entry("necromancy", ScriptCategory.NECROMANCY),
            Map.entry("prayer", ScriptCategory.PRAYER),
            Map.entry("runecrafting", ScriptCategory.RUNECRAFTING),
            Map.entry("slayer", ScriptCategory.SLAYER),
            Map.entry("smithing", ScriptCategory.SMITHING),
            Map.entry("summoning", ScriptCategory.SUMMONING),
            Map.entry("thieving", ScriptCategory.THIEVING),
            Map.entry("woodcutting", ScriptCategory.WOODCUTTING));

    private CategorySlugs() {
    }

    /**
     * The host category for {@code slug}: {@link ScriptCategory#UNCATEGORIZED} for
     * {@code null} and for any slug this table does not know, so a category the
     * site adds later still shows, just without a category of its own. Case and
     * surrounding spaces are ignored.
     */
    public static ScriptCategory toCategory(String slug) {
        if (slug == null) {
            return ScriptCategory.UNCATEGORIZED;
        }
        return BY_SLUG.getOrDefault(slug.strip().toLowerCase(Locale.ROOT), ScriptCategory.UNCATEGORIZED);
    }
}
