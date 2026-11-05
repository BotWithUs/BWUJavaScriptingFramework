package com.botwithus.bot.core.impl.snapshot;

import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.core.shm.Layout;
import com.botwithus.bot.core.shm.SnapshotView;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.function.Consumer;

/**
 * The snapshot frames with no local player, as the bytes a consumer reads, plus the valid in-world
 * frame they must not be confused with.
 *
 * <p>The offsets come from {@link Layout} because this fixture is about the gate, not the layout:
 * {@code LayoutWireOffsetsTest} and {@code ProducerHeaderDriftTest} pin the offsets themselves.</p>
 */
public enum NoWorldFrame {
    /** A mapping the agent has not published to: demand-zero, so index 0 looks valid. */
    NEVER_PUBLISHED(seg -> { }),
    /** What the agent publishes at state 30 with no logged-in player, during login. */
    STATE_30_RESET(seg -> writeReset(seg, GameSnapshot.GAME_STATE_IN_GAME)),
    /** What the agent publishes in the lobby. */
    LOBBY_RESET(NoWorldFrame::writeLobbyReset),
    /** Not a shape the agent publishes: a lobby state over a self block that looks complete. */
    LOBBY_OVER_STALE_SELF(NoWorldFrame::writeLobbyOverStaleSelf),
    /** The pre-publish frame once the agent initialises the mapping with explicit sentinels. */
    SENTINEL_INITIALISED(NoWorldFrame::writeSentinels);

    public static final int TILE_X = 3222;
    public static final int TILE_Y = 3235;
    public static final int SERVER_TILE_X = 3225;
    public static final int SERVER_TILE_Y = 3236;
    public static final int PLANE = 1;
    public static final int RUN_ENERGY = 87;
    public static final int RUN_WEIGHT = 14;
    public static final int NPC_TILE_X = 3;
    public static final int NPC_TILE_Y = 4;

    private static final int STATE_LOBBY = 20;
    private static final int NO_INDEX = -1;
    private static final int STALE_INDEX = 7;
    private static final int STALE_SKILL = 6;
    private static final short ORIENTATION_UNKNOWN = (short) 0xFFFF;

    private final Consumer<MemorySegment> shape;

    NoWorldFrame(Consumer<MemorySegment> shape) {
        this.shape = shape;
    }

    /** This frame's snapshot. The memory is not confined, so another thread may read it. */
    public GameSnapshot snapshot() {
        MemorySegment seg = allocate();
        shape.accept(seg);
        return read(seg);
    }

    /**
     * A valid in-world frame at own index 0, the index that must still count as a player, with one
     * NPC and one projectile aimed at {@code projectileTarget}.
     */
    public static GameSnapshot inWorldAtIndexZero(int projectileTarget) {
        MemorySegment seg = allocate();
        seg.set(ValueLayout.JAVA_INT, Layout.SNAP_GAMESTATE_OFFSET, GameSnapshot.GAME_STATE_IN_GAME);
        seg.set(ValueLayout.JAVA_INT, Layout.SNAP_OWNINDEX_OFFSET, 0);
        writeSelfTile(seg);
        seg.set(ValueLayout.JAVA_SHORT, selfField(Layout.LP_SERVERTILEX_OFFSET), (short) SERVER_TILE_X);
        seg.set(ValueLayout.JAVA_SHORT, selfField(Layout.LP_SERVERTILEY_OFFSET), (short) SERVER_TILE_Y);
        seg.set(ValueLayout.JAVA_BYTE, selfField(Layout.LP_SERVERPLANE_OFFSET), (byte) PLANE);
        seg.set(ValueLayout.JAVA_INT, selfField(Layout.LP_RUNENERGY_OFFSET), RUN_ENERGY);
        seg.set(ValueLayout.JAVA_INT, selfField(Layout.LP_RUNWEIGHT_OFFSET), RUN_WEIGHT);
        seg.set(ValueLayout.JAVA_INT, Layout.SNAP_NPCCOUNT_OFFSET, 1);
        seg.set(ValueLayout.JAVA_SHORT, Layout.SNAP_NPCS_OFFSET + Layout.NPC_TILEX_OFFSET, (short) NPC_TILE_X);
        seg.set(ValueLayout.JAVA_SHORT, Layout.SNAP_NPCS_OFFSET + Layout.NPC_TILEY_OFFSET, (short) NPC_TILE_Y);
        seg.set(ValueLayout.JAVA_INT, Layout.SNAP_PROJECTILECOUNT_OFFSET, 1);
        seg.set(ValueLayout.JAVA_SHORT, Layout.SNAP_PROJECTILES_OFFSET + Layout.PROJECTILE_TARGETINDEX_OFFSET,
                (short) projectileTarget);
        return read(seg);
    }

    private static void writeReset(MemorySegment seg, int gameState) {
        seg.set(ValueLayout.JAVA_INT, Layout.SNAP_GAMESTATE_OFFSET, gameState);
        seg.set(ValueLayout.JAVA_INT, Layout.SNAP_OWNINDEX_OFFSET, NO_INDEX);
        seg.set(ValueLayout.JAVA_SHORT, selfField(Layout.LP_ORIENTATION_OFFSET), ORIENTATION_UNKNOWN);
    }

    private static void writeLobbyReset(MemorySegment seg) {
        writeReset(seg, STATE_LOBBY);
    }

    private static void writeLobbyOverStaleSelf(MemorySegment seg) {
        seg.set(ValueLayout.JAVA_INT, Layout.SNAP_GAMESTATE_OFFSET, STATE_LOBBY);
        seg.set(ValueLayout.JAVA_INT, Layout.SNAP_OWNINDEX_OFFSET, STALE_INDEX);
        seg.set(ValueLayout.JAVA_INT, selfField(Layout.LP_SERVERINDEX_OFFSET), STALE_INDEX);
        writeSelfTile(seg);
        seg.set(ValueLayout.JAVA_INT, selfField(Layout.LP_SKILLCOUNT_OFFSET), 1);
        seg.set(ValueLayout.JAVA_INT, selfField(Layout.LP_SKILLS_OFFSET + Layout.SKILL_TYPEID_OFFSET), STALE_SKILL);
    }

    private static void writeSentinels(MemorySegment seg) {
        seg.set(ValueLayout.JAVA_INT, Layout.SNAP_OWNINDEX_OFFSET, NO_INDEX);
        seg.set(ValueLayout.JAVA_INT, selfField(Layout.LP_SERVERINDEX_OFFSET), NO_INDEX);
        seg.set(ValueLayout.JAVA_SHORT, selfField(Layout.LP_SERVERTILEX_OFFSET), (short) NO_INDEX);
        seg.set(ValueLayout.JAVA_SHORT, selfField(Layout.LP_SERVERTILEY_OFFSET), (short) NO_INDEX);
        seg.set(ValueLayout.JAVA_BYTE, selfField(Layout.LP_SERVERPLANE_OFFSET), (byte) NO_INDEX);
        seg.set(ValueLayout.JAVA_SHORT, selfField(Layout.LP_ORIENTATION_OFFSET), ORIENTATION_UNKNOWN);
    }

    private static void writeSelfTile(MemorySegment seg) {
        seg.set(ValueLayout.JAVA_SHORT, selfField(Layout.LP_TILEX_OFFSET), (short) TILE_X);
        seg.set(ValueLayout.JAVA_SHORT, selfField(Layout.LP_TILEY_OFFSET), (short) TILE_Y);
        seg.set(ValueLayout.JAVA_BYTE, selfField(Layout.LP_PLANE_OFFSET), (byte) PLANE);
    }

    private static long selfField(int fieldOffset) {
        return Layout.SNAP_SELF_OFFSET + (long) fieldOffset;
    }

    private static MemorySegment allocate() {
        return Arena.ofAuto().allocate(Layout.SNAPSHOT_SIZE, Long.BYTES);
    }

    private static GameSnapshot read(MemorySegment seg) {
        return new GameSnapshotImpl(new SnapshotView(seg));
    }
}
