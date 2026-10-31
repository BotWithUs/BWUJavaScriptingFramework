package com.botwithus.bot.core.worldwalker;

import com.botwithus.bot.api.model.WorldPathConfig;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The free-to-play bit is set only when the setting is on and the account is not a member;
 * the movement-category bits mirror the config's {@code allow*} flags one for one.
 */
class DisabledMovesTest {

    /** {@code WW_RESTRICT_FREE_TO_PLAY} in {@code worldwalker_c.h}: {@code (1u << 31)}. */
    private static final int HEADER_BIT = 0x8000_0000;

    @Test
    void theBit_matchesTheHeader() {
        assertEquals(HEADER_BIT, DisabledMoves.RESTRICT_FREE_TO_PLAY_BIT);
        assertEquals(HEADER_BIT, DisabledMoves.FREE_TO_PLAY.mask());
        assertEquals(0, DisabledMoves.NONE.mask());
    }

    @Test
    void settingOff_neverSetsTheBit() {
        assertAll(
                () -> assertEquals(DisabledMoves.NONE, DisabledMoves.forWalk(false, self(false))),
                () -> assertEquals(DisabledMoves.NONE, DisabledMoves.forWalk(false, self(true))),
                () -> assertEquals(DisabledMoves.NONE, DisabledMoves.forWalk(false, null)));
    }

    @Test
    void settingOn_restrictsOnlyAFreeToPlayAccount() {
        assertAll(
                () -> assertEquals(DisabledMoves.FREE_TO_PLAY, DisabledMoves.forWalk(true, self(false))),
                () -> assertEquals(DisabledMoves.NONE, DisabledMoves.forWalk(true, self(true))));
    }

    @Test
    void settingOn_withNoLocalPlayer_plansUnrestricted() {
        assertEquals(DisabledMoves.NONE, DisabledMoves.forWalk(true, null));
    }

    @Test
    void predicates_readTheMask() {
        assertAll(
                () -> assertTrue(DisabledMoves.NONE.isNone()),
                () -> assertFalse(DisabledMoves.NONE.isFreeToPlay()),
                () -> assertFalse(DisabledMoves.FREE_TO_PLAY.isNone()),
                () -> assertTrue(DisabledMoves.FREE_TO_PLAY.isFreeToPlay()));
    }

    // ===================== Movement categories =====================

    /** {@code WW_MOVE_DOORS} .. {@code WW_MOVE_OTHER_CHAINS} in {@code worldwalker_c.h}. */
    private static final int HEADER_DOORS = 0;
    private static final int HEADER_OTHER_CHAINS = 12;
    /** Every category bit, {@code (1u << WW_MOVE_COUNT) - 1}. */
    private static final int ALL_CATEGORIES = 0x1FFF;

    @Test
    void categoryBits_matchTheHeader() {
        assertAll(
                () -> assertEquals(1 << HEADER_DOORS, DisabledMoves.DOORS_BIT),
                () -> assertEquals(1 << 1, DisabledMoves.SHORTCUTS_BIT),
                () -> assertEquals(1 << 2, DisabledMoves.PLANE_BIT),
                () -> assertEquals(1 << 3, DisabledMoves.CLIMBOVERS_BIT),
                () -> assertEquals(1 << 4, DisabledMoves.TRANSPORTS_BIT),
                () -> assertEquals(1 << 5, DisabledMoves.TELEPORTS_BIT),
                () -> assertEquals(1 << 6, DisabledMoves.LODESTONES_BIT),
                () -> assertEquals(1 << 7, DisabledMoves.FAIRY_RINGS_BIT),
                () -> assertEquals(1 << 8, DisabledMoves.SPIRIT_TREES_BIT),
                () -> assertEquals(1 << 9, DisabledMoves.GLIDERS_BIT),
                () -> assertEquals(1 << 10, DisabledMoves.CHARTERS_BIT),
                () -> assertEquals(1 << 11, DisabledMoves.MAGIC_CARPETS_BIT),
                () -> assertEquals(1 << HEADER_OTHER_CHAINS, DisabledMoves.OTHER_CHAINS_BIT));
    }

    @Test
    void forConfig_defaultOrNull_disablesNothing() {
        assertAll(
                () -> assertEquals(DisabledMoves.NONE, DisabledMoves.forConfig(null)),
                () -> assertEquals(DisabledMoves.NONE,
                        DisabledMoves.forConfig(WorldPathConfig.DEFAULT)),
                () -> assertEquals(DisabledMoves.NONE,
                        DisabledMoves.forConfig(WorldPathConfig.builder().build())));
    }

    @Test
    void forConfig_eachFlag_setsExactlyItsOwnBit() {
        assertAll(
                () -> assertBit(DisabledMoves.DOORS_BIT, b -> b.allowDoors(false)),
                () -> assertBit(DisabledMoves.SHORTCUTS_BIT, b -> b.allowShortcuts(false)),
                () -> assertBit(DisabledMoves.PLANE_BIT, b -> b.allowPlaneTransitions(false)),
                () -> assertBit(DisabledMoves.CLIMBOVERS_BIT, b -> b.allowClimbovers(false)),
                () -> assertBit(DisabledMoves.TRANSPORTS_BIT, b -> b.allowTransports(false)),
                () -> assertBit(DisabledMoves.TELEPORTS_BIT, b -> b.allowTeleports(false)),
                () -> assertBit(DisabledMoves.LODESTONES_BIT, b -> b.allowLodestones(false)),
                () -> assertBit(DisabledMoves.FAIRY_RINGS_BIT, b -> b.allowFairyRings(false)),
                () -> assertBit(DisabledMoves.SPIRIT_TREES_BIT, b -> b.allowSpiritTrees(false)),
                () -> assertBit(DisabledMoves.GLIDERS_BIT, b -> b.allowGliders(false)),
                () -> assertBit(DisabledMoves.CHARTERS_BIT, b -> b.allowCharters(false)),
                () -> assertBit(DisabledMoves.MAGIC_CARPETS_BIT, b -> b.allowMagicCarpets(false)),
                () -> assertBit(DisabledMoves.OTHER_CHAINS_BIT,
                        b -> b.allowOtherTeleportChains(false)));
    }

    @Test
    void forConfig_surgeAndDive_haveNoBit() {
        WorldPathConfig config = WorldPathConfig.builder().allowSurge(false).allowDive(false).build();

        assertEquals(DisabledMoves.NONE, DisabledMoves.forConfig(config));
    }

    @Test
    void forConfig_everythingOff_setsEveryCategoryAndNotFreeToPlay() {
        WorldPathConfig config = WorldPathConfig.builder()
                .allowDoors(false).allowShortcuts(false).allowPlaneTransitions(false)
                .allowClimbovers(false).allowTransports(false).allowTeleports(false)
                .allowLodestones(false).allowFairyRings(false).allowSpiritTrees(false)
                .allowGliders(false).allowCharters(false).allowMagicCarpets(false)
                .allowOtherTeleportChains(false)
                .build();

        DisabledMoves moves = DisabledMoves.forConfig(config);

        assertEquals(ALL_CATEGORIES, moves.mask());
        assertFalse(moves.isFreeToPlay());
        assertTrue(moves.hasCategories());
    }

    @Test
    void with_combinesFreeToPlayAndCategories() {
        DisabledMoves categories = DisabledMoves.forConfig(
                WorldPathConfig.builder().allowLodestones(false).allowDoors(false).build());

        DisabledMoves combined = DisabledMoves.FREE_TO_PLAY.with(categories);

        assertEquals(HEADER_BIT | DisabledMoves.LODESTONES_BIT | DisabledMoves.DOORS_BIT,
                combined.mask());
        assertTrue(combined.isFreeToPlay());
        assertTrue(combined.hasCategories());
        assertEquals(combined, categories.with(DisabledMoves.FREE_TO_PLAY));
    }

    @Test
    void with_none_isIdentity() {
        assertAll(
                () -> assertEquals(DisabledMoves.NONE, DisabledMoves.NONE.with(DisabledMoves.NONE)),
                () -> assertEquals(DisabledMoves.FREE_TO_PLAY,
                        DisabledMoves.FREE_TO_PLAY.with(DisabledMoves.NONE)),
                () -> assertFalse(DisabledMoves.FREE_TO_PLAY.hasCategories()));
    }

    private static void assertBit(int expected, UnaryOperator<WorldPathConfig.Builder> off) {
        assertEquals(expected,
                DisabledMoves.forConfig(off.apply(WorldPathConfig.builder()).build()).mask());
    }

    private static LocalPlayer self(boolean isMember) {
        return new LocalPlayer(0, 0, 3222, 3218, 0, 0, -1, -1, 0, -1, 0, isMember, -1,
                LocalPlayer.HEALTH_UNKNOWN, LocalPlayer.HEALTH_UNKNOWN, List.of());
    }
}
