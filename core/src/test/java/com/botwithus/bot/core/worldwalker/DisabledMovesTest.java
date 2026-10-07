package com.botwithus.bot.core.worldwalker;

import com.botwithus.bot.api.snapshot.LocalPlayer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The free-to-play bit is set only when the setting is on and the account is not a member. */
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

    private static LocalPlayer self(boolean isMember) {
        return new LocalPlayer(0, 0, 3222, 3218, 0, 0, -1, -1, 0, -1, 0, isMember, -1,
                LocalPlayer.HEALTH_UNKNOWN, LocalPlayer.HEALTH_UNKNOWN, List.of());
    }
}
