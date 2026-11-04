package com.botwithus.bot.core.impl.snapshot;

import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Player;
import com.botwithus.bot.core.shm.Layout;
import com.botwithus.bot.core.shm.SnapshotView;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The v25 server tile, decoded end to end through {@link GameSnapshotImpl}.
 *
 * <p>Bytes are written at <b>literal</b> offsets from NXTLibrary's SharedLayout.h at f6fdf35, not
 * through {@link Layout}: a fixture written through the reader's own constants would round-trip
 * whatever they said. Row 1 is written as well as row 0 so the new stride is pinned with the fields,
 * and each visual tile differs from its server tile so that reading one for the other fails.</p>
 */
class GameSnapshotV25FieldsTest {

    private static final long GAME_STATE = 8;
    private static final long OWN_INDEX = 12;
    private static final long SELF = 24;
    private static final long SELF_TILE_X = 8;
    private static final long SELF_TILE_Y = 10;
    private static final long SELF_SERVER_TILE_X = 560;
    private static final long SELF_SERVER_TILE_Y = 562;
    private static final long SELF_SERVER_PLANE = 564;
    private static final long PLAYER_COUNT = 49748;
    private static final long PLAYERS = 49752;
    private static final long PLAYER_STRIDE = 56;
    private static final long PLAYER_TILE_X = 4;
    private static final long PLAYER_TILE_Y = 6;
    private static final long PLAYER_SERVER_TILE_X = 48;
    private static final long PLAYER_SERVER_TILE_Y = 50;
    private static final long PLAYER_SERVER_PLANE = 52;

    private static final int IN_GAME = 30;
    private static final int LOBBY = 20;
    private static final int OWN_SERVER_INDEX = 7;
    private static final int UNKNOWN = -1;

    private static final short VISUAL_X = 3222;
    private static final short VISUAL_Y = 3235;
    private static final short SERVER_X = 3225;
    private static final short SERVER_Y = 3236;
    private static final byte SERVER_PLANE = 2;
    private static final short OTHER_SERVER_X = 3100;
    private static final short OTHER_SERVER_Y = 3101;
    private static final byte OTHER_SERVER_PLANE = 1;

    @Test
    void playerRows_carryTheServerTileApartFromTheVisualTile() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(Layout.SNAPSHOT_SIZE, Long.BYTES);
            seg.set(ValueLayout.JAVA_INT, PLAYER_COUNT, 2);
            writePlayer(seg, 0, SERVER_X, SERVER_Y, SERVER_PLANE);
            writePlayer(seg, 1, OTHER_SERVER_X, OTHER_SERVER_Y, OTHER_SERVER_PLANE);

            GameSnapshot snap = new GameSnapshotImpl(new SnapshotView(seg));
            Player first = snap.players().at(0);
            Player second = snap.players().at(1);

            assertEquals(VISUAL_X, first.tileX(), "tileX stays the visual tile");
            assertEquals(VISUAL_Y, first.tileY(), "tileY stays the visual tile");
            assertEquals(SERVER_X, first.serverTileX());
            assertEquals(SERVER_Y, first.serverTileY());
            assertEquals(SERVER_PLANE, first.serverPlane());
            assertTrue(first.hasServerTile());
            assertEquals(OTHER_SERVER_X, second.serverTileX());
            assertEquals(OTHER_SERVER_Y, second.serverTileY());
            assertEquals(OTHER_SERVER_PLANE, second.serverPlane());
        }
    }

    /** -1 is a sign-extended i16 and i8, so a mask here would turn it into a plausible tile. */
    @Test
    void playerRow_unreadable_readsMinusOneInAllThree() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(Layout.SNAPSHOT_SIZE, Long.BYTES);
            seg.set(ValueLayout.JAVA_INT, PLAYER_COUNT, 1);
            writePlayer(seg, 0, (short) UNKNOWN, (short) UNKNOWN, (byte) UNKNOWN);

            Player p = new GameSnapshotImpl(new SnapshotView(seg)).players().at(0);

            assertEquals(Player.SERVER_TILE_UNKNOWN, p.serverTileX());
            assertEquals(Player.SERVER_TILE_UNKNOWN, p.serverTileY());
            assertEquals(Player.SERVER_TILE_UNKNOWN, p.serverPlane());
            assertFalse(p.hasServerTile());
        }
    }

    @Test
    void self_inWorld_carriesTheServerTileApartFromTheVisualTile() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = selfSnapshot(arena, IN_GAME, SERVER_X, SERVER_Y, SERVER_PLANE);

            LocalPlayer self = new GameSnapshotImpl(new SnapshotView(seg)).self();

            assertEquals(VISUAL_X, self.tileX(), "tileX stays the visual tile");
            assertEquals(VISUAL_Y, self.tileY(), "tileY stays the visual tile");
            assertEquals(SERVER_X, self.serverTileX());
            assertEquals(SERVER_Y, self.serverTileY());
            assertEquals(SERVER_PLANE, self.serverPlane());
            assertTrue(self.hasServerTile());
        }
    }

    @Test
    void self_inWorldButUnreadable_passesTheWireUnknownThrough() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = selfSnapshot(arena, IN_GAME, (short) UNKNOWN, (short) UNKNOWN, (byte) UNKNOWN);

            LocalPlayer self = new GameSnapshotImpl(new SnapshotView(seg)).self();

            assertEquals(LocalPlayer.SERVER_TILE_UNKNOWN, self.serverTileX());
            assertFalse(self.hasServerTile());
        }
    }

    /** The self block is zero-filled out of the world; that must not read as a tile at (0,0). */
    @Test
    void self_outOfWorld_reportsUnknownRatherThanTheWireZero() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = selfSnapshot(arena, LOBBY, (short) 0, (short) 0, (byte) 0);

            LocalPlayer self = new GameSnapshotImpl(new SnapshotView(seg)).self();

            assertEquals(LocalPlayer.SERVER_TILE_UNKNOWN, self.serverTileX());
            assertEquals(LocalPlayer.SERVER_TILE_UNKNOWN, self.serverTileY());
            assertEquals(LocalPlayer.SERVER_TILE_UNKNOWN, self.serverPlane());
            assertFalse(self.hasServerTile());
        }
    }

    @Test
    void withHealth_keepsTheServerTile() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = selfSnapshot(arena, IN_GAME, SERVER_X, SERVER_Y, SERVER_PLANE);

            LocalPlayer self = new GameSnapshotImpl(new SnapshotView(seg)).self().withHealth(1, 2);

            assertEquals(SERVER_X, self.serverTileX());
            assertEquals(SERVER_Y, self.serverTileY());
            assertEquals(SERVER_PLANE, self.serverPlane());
        }
    }

    private static MemorySegment selfSnapshot(Arena arena, int gameState, short x, short y, byte plane) {
        MemorySegment seg = arena.allocate(Layout.SNAPSHOT_SIZE, Long.BYTES);
        seg.set(ValueLayout.JAVA_INT, GAME_STATE, gameState);
        seg.set(ValueLayout.JAVA_INT, OWN_INDEX, OWN_SERVER_INDEX);
        seg.set(ValueLayout.JAVA_INT, SELF, OWN_SERVER_INDEX);
        seg.set(ValueLayout.JAVA_SHORT, SELF + SELF_TILE_X, VISUAL_X);
        seg.set(ValueLayout.JAVA_SHORT, SELF + SELF_TILE_Y, VISUAL_Y);
        seg.set(ValueLayout.JAVA_SHORT, SELF + SELF_SERVER_TILE_X, x);
        seg.set(ValueLayout.JAVA_SHORT, SELF + SELF_SERVER_TILE_Y, y);
        seg.set(ValueLayout.JAVA_BYTE, SELF + SELF_SERVER_PLANE, plane);
        return seg;
    }

    private static void writePlayer(MemorySegment seg, int row, short serverX, short serverY, byte serverPlane) {
        long base = PLAYERS + row * PLAYER_STRIDE;
        seg.set(ValueLayout.JAVA_SHORT, base + PLAYER_TILE_X, VISUAL_X);
        seg.set(ValueLayout.JAVA_SHORT, base + PLAYER_TILE_Y, VISUAL_Y);
        seg.set(ValueLayout.JAVA_SHORT, base + PLAYER_SERVER_TILE_X, serverX);
        seg.set(ValueLayout.JAVA_SHORT, base + PLAYER_SERVER_TILE_Y, serverY);
        seg.set(ValueLayout.JAVA_BYTE, base + PLAYER_SERVER_PLANE, serverPlane);
    }
}
