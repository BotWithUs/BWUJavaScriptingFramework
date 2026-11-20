package com.botwithus.bot.api.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two xp curves at well-known anchor levels and checks that level and xp lookups
 * round-trip at every level boundary.
 */
class XpTableTest {

    @ParameterizedTest
    @CsvSource({
            "1, 0",
            "2, 83",
            "10, 1154",
            "50, 101333",
            "92, 6517253",
            "99, 13034431",
            "120, 104273167",
            "126, 188884740",
    })
    void standard_xpForLevel_matchesTheGameCurve(int level, int xp) {
        assertEquals(xp, XpTable.STANDARD.xpForLevel(level));
    }

    @ParameterizedTest
    @CsvSource({
            "1, 0",
            "2, 830",
            "99, 36073511",
            "120, 80618654",
    })
    void elite_xpForLevel_matchesTheGameCurve(int level, int xp) {
        assertEquals(xp, XpTable.ELITE.xpForLevel(level));
    }

    @ParameterizedTest
    @EnumSource(XpTable.class)
    void levelForXp_atAndJustBelowEachBoundary_roundTrips(XpTable table) {
        for (int level = 2; level <= table.maxLevel(); level++) {
            int xp = table.xpForLevel(level);
            int lvl = level;
            assertAll(
                    () -> assertEquals(lvl, table.levelForXp(xp)),
                    () -> assertEquals(lvl - 1, table.levelForXp(xp - 1)));
        }
    }

    @Test
    void levelForXp_beyondTheTop_readsTheTopLevel() {
        assertEquals(XpTable.STANDARD_TABLE_TOP, XpTable.STANDARD.levelForXp(XpTable.MAX_XP));
        assertEquals(XpTable.ELITE_TABLE_TOP, XpTable.ELITE.levelForXp(XpTable.MAX_XP));
    }

    @Test
    void levelForXp_negativeXp_readsLevelOne() {
        assertEquals(XpTable.MIN_LEVEL, XpTable.STANDARD.levelForXp(-1));
    }

    @Test
    void levelForXp_withCap_capsTheVirtualLevel() {
        int capped = 99;
        assertEquals(capped, XpTable.STANDARD.levelForXp(XpTable.MAX_XP, capped));
    }

    @Test
    void xpToNextLevel_midLevel_isTheGapToTheNextBoundary() {
        assertEquals(83 - 50, XpTable.STANDARD.xpToNextLevel(50));
        assertEquals(1154 - 969, XpTable.STANDARD.xpToNextLevel(969));
    }

    @Test
    void xpToNextLevel_atTheTop_isZero() {
        assertEquals(0, XpTable.STANDARD.xpToNextLevel(XpTable.MAX_XP));
    }

    @Test
    void xpForLevel_outOfRange_throws() {
        assertThrows(IllegalArgumentException.class, () -> XpTable.STANDARD.xpForLevel(0));
        assertThrows(IllegalArgumentException.class,
                () -> XpTable.STANDARD.xpForLevel(XpTable.STANDARD_TABLE_TOP + 1));
    }

    @Test
    void tables_areStrictlyIncreasingAndWithinTheXpCap() {
        for (XpTable table : XpTable.values()) {
            for (int level = 2; level <= table.maxLevel(); level++) {
                assertTrue(table.xpForLevel(level) > table.xpForLevel(level - 1), table + " " + level);
            }
            assertTrue(table.xpForLevel(table.maxLevel()) <= XpTable.MAX_XP);
        }
    }
}
