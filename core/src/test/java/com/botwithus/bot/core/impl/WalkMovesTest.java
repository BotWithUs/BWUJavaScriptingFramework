package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.model.WorldPathConfig;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.worldwalker.DisabledMoves;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The mask a connection hands the walker: off until the host turns the setting
 * on, and then only for an account the snapshot reads as not a member; plus one
 * movement-category bit per {@code allow*} flag the walk's config turns off.
 */
class WalkMovesTest {

    private final AtomicReference<GameSnapshot> snapshot = new AtomicReference<>();
    private final GameAPIImpl api = new GameAPIImpl(mock(RpcClient.class), null, snapshot::get);

    @Test
    void byDefault_noWalkIsRestricted() {
        snapshot.set(snapshotOf(self(false)));

        assertEquals(DisabledMoves.NONE, api.walkMoves());
    }

    @Test
    void settingOn_freeToPlayAccount_isRestricted() {
        snapshot.set(snapshotOf(self(false)));
        api.setRestrictFreeToPlay(true);

        assertEquals(DisabledMoves.FREE_TO_PLAY, api.walkMoves());
    }

    @Test
    void settingOn_memberAccount_isNotRestricted() {
        snapshot.set(snapshotOf(self(true)));
        api.setRestrictFreeToPlay(true);

        assertEquals(DisabledMoves.NONE, api.walkMoves());
    }

    @Test
    void settingTurnedOffAgain_stopsRestricting() {
        snapshot.set(snapshotOf(self(false)));
        api.setRestrictFreeToPlay(true);
        api.setRestrictFreeToPlay(false);

        assertEquals(DisabledMoves.NONE, api.walkMoves());
    }

    @Test
    void settingOn_noSnapshot_isNotRestricted() {
        api.setRestrictFreeToPlay(true);

        assertEquals(DisabledMoves.NONE, api.walkMoves());
    }

    @Test
    void aConfig_disablesItsCategories_withoutTheSettingOn() {
        snapshot.set(snapshotOf(self(false)));
        WorldPathConfig config = WorldPathConfig.builder().allowLodestones(false).build();

        assertEquals(new DisabledMoves(DisabledMoves.LODESTONES_BIT), api.walkMoves(config));
    }

    @Test
    void aConfig_andFreeToPlay_combineIntoOneMask() {
        snapshot.set(snapshotOf(self(false)));
        api.setRestrictFreeToPlay(true);
        WorldPathConfig config = WorldPathConfig.builder()
                .allowFairyRings(false).allowDoors(false).build();

        assertEquals(DisabledMoves.FREE_TO_PLAY.with(new DisabledMoves(
                DisabledMoves.FAIRY_RINGS_BIT | DisabledMoves.DOORS_BIT)), api.walkMoves(config));
    }

    @Test
    void theDefaultConfig_addsNothingToTheSetting() {
        snapshot.set(snapshotOf(self(false)));
        api.setRestrictFreeToPlay(true);

        assertEquals(DisabledMoves.FREE_TO_PLAY, api.walkMoves(WorldPathConfig.DEFAULT));
    }

    private static GameSnapshot snapshotOf(LocalPlayer self) {
        GameSnapshot snap = mock(GameSnapshot.class);
        when(snap.self()).thenReturn(self);
        return snap;
    }

    private static LocalPlayer self(boolean isMember) {
        return new LocalPlayer(0, 0, 3222, 3218, 0, 0, -1, -1, 0, -1, 0, isMember, -1,
                LocalPlayer.HEALTH_UNKNOWN, LocalPlayer.HEALTH_UNKNOWN, List.of());
    }
}
