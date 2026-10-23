package com.botwithus.bot.core.sdn;

import com.botwithus.bot.api.ScriptCategory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategorySlugsTest {

    /**
     * Every category the site offers: the values of {@code Script.CATEGORIES} in the
     * BotWithUs website's {@code app/sdn/models.py}, copied at website commit
     * {@code 3267d4d}, which is the commit that started sending them to the host.
     * A slug the site adds later is not a failure here (it shows as Uncategorized);
     * copy it in, and give it a mapping, when the site adds it.
     */
    private static final List<String> SERVER_SLUGS = List.of(
            "combat", "mining", "fishing", "smithing", "crafting", "woodcutting",
            "money_making", "firemaking", "herblore", "cooking", "runecrafting", "misc",
            "necromancy", "divination", "construction", "fletching", "agility", "prayer",
            "farming", "summoning", "thieving", "invention", "hunter", "mini_games",
            "magic", "slayer");

    @Test
    void toCategory_everyServerSlug_hasARealCategory() {
        for (String slug : SERVER_SLUGS) {
            assertNotEquals(ScriptCategory.UNCATEGORIZED, CategorySlugs.toCategory(slug),
                    () -> "server slug '" + slug + "' fell through to Uncategorized");
        }
    }

    @Test
    void toCategory_serverSlugs_neverShareACategory() {
        Set<ScriptCategory> seen = EnumSet.noneOf(ScriptCategory.class);
        for (String slug : SERVER_SLUGS) {
            ScriptCategory category = CategorySlugs.toCategory(slug);
            assertTrue(seen.add(category),
                    () -> "server slug '" + slug + "' maps onto " + category + ", which another slug already uses");
        }
    }

    /** The slugs whose host name differs from the slug, so a mechanical upper-casing would get them wrong. */
    @ParameterizedTest
    @CsvSource({
        "money_making, MONEYMAKING",
        "mini_games, MINIGAME",
        "misc, OTHER",
        "firemaking, FIREMAKING",
        "magic, MAGIC",
        "woodcutting, WOODCUTTING"
    })
    void toCategory_serverSlug_mapsToItsHostCategory(String slug, ScriptCategory expected) {
        assertEquals(expected, CategorySlugs.toCategory(slug));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "underwater_basket_weaving", "moneymaking"})
    void toCategory_unknownSlug_isUncategorized(String slug) {
        assertEquals(ScriptCategory.UNCATEGORIZED, CategorySlugs.toCategory(slug));
    }

    @Test
    void toCategory_null_isUncategorized() {
        assertEquals(ScriptCategory.UNCATEGORIZED, CategorySlugs.toCategory(null));
    }

    @Test
    void toCategory_differentCaseOrPadding_stillMaps() {
        assertEquals(ScriptCategory.MONEYMAKING, CategorySlugs.toCategory(" Money_Making "));
    }
}
