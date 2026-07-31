package com.botwithus.bot.test;

import com.botwithus.bot.api.snapshot.LocalPlayer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CannedSnapshotTest {

    private static final int SAMPLE_TILE_X = 3222;
    private static final int SAMPLE_TILE_Y = 3219;
    private static final int SAMPLE_PLANE = 0;
    private static final int SAMPLE_SERVER_INDEX = 2046;
    private static final int SAMPLE_COMBAT_LEVEL = 138;

    @Test
    void empty_hasNoSelfAndLoginState() {
        CannedSnapshot snapshot = CannedSnapshot.empty();

        assertAll(
                () -> assertNull(snapshot.self()),
                () -> assertEquals(CannedSnapshot.GAME_STATE_LOGIN, snapshot.gameState()),
                () -> assertEquals(-1, snapshot.ownIndex()),
                () -> assertNotNull(snapshot.npcs()),
                () -> assertEquals(0, snapshot.npcs().count()),
                () -> assertEquals(0, snapshot.players().count()),
                () -> assertEquals(0, snapshot.inventories().count()));
    }

    @Test
    void withSelf_marksInGameAndCopiesIndex() {
        LocalPlayer self = sampleLocalPlayer();

        CannedSnapshot snapshot = CannedSnapshot.withSelf(self);

        assertAll(
                () -> assertEquals(self, snapshot.self()),
                () -> assertEquals(CannedSnapshot.GAME_STATE_IN_GAME, snapshot.gameState()),
                () -> assertEquals(SAMPLE_SERVER_INDEX, snapshot.ownIndex()));
    }

    @Test
    void withSelf_nullSelf_throws() {
        assertThrows(IllegalArgumentException.class, () -> CannedSnapshot.withSelf(null));
    }

    @Test
    void withServerTick_preservesEverythingElse() {
        CannedSnapshot snapshot = CannedSnapshot.withSelf(sampleLocalPlayer()).withServerTick(42);

        assertAll(
                () -> assertEquals(42, snapshot.serverTick()),
                () -> assertEquals(SAMPLE_SERVER_INDEX, snapshot.ownIndex()),
                () -> assertEquals(CannedSnapshot.GAME_STATE_IN_GAME, snapshot.gameState()));
    }

    @Test
    void withGameCycle_leavesServerTickAlone() {
        CannedSnapshot snapshot = CannedSnapshot.withSelf(sampleLocalPlayer())
                .withServerTick(42)
                .withGameCycle(1_260);

        assertAll(
                () -> assertEquals(42, snapshot.serverTick()),
                () -> assertEquals(1_260, snapshot.gameCycle()));
    }

    @Test
    void npcs_atOutOfRange_returnsNull() {
        CannedSnapshot snapshot = CannedSnapshot.empty();

        assertAll(
                () -> assertNull(snapshot.npcs().at(0)),
                () -> assertNull(snapshot.npcs().at(-1)),
                () -> assertNull(snapshot.players().at(0)),
                () -> assertNull(snapshot.inventories().at(0)));
    }

    private static LocalPlayer sampleLocalPlayer() {
        return new LocalPlayer(
                SAMPLE_SERVER_INDEX,
                SAMPLE_COMBAT_LEVEL,
                SAMPLE_TILE_X,
                SAMPLE_TILE_Y,
                SAMPLE_PLANE,
                0,
                -1,
                -1,
                0,
                -1,
                0,
                true,
                -1,
                List.of());
    }
}
