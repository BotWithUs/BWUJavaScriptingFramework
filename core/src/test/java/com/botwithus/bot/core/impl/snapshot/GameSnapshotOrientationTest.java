package com.botwithus.bot.core.impl.snapshot;

import com.botwithus.bot.api.snapshot.Direction;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.Orientation;
import com.botwithus.bot.core.shm.Layout;
import com.botwithus.bot.core.shm.OrientationWireDecoder;
import com.botwithus.bot.core.shm.SnapshotView;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire v21 facing, decoded end to end through {@link GameSnapshotImpl}.
 *
 * <p>The producer's bytes are written at <b>literal</b> offsets taken from NXTLibrary's
 * SharedLayout.h at the v21 header, not from {@link Layout}. A test that wrote through the
 * same constants the reader uses would pass whatever those constants said. Each case writes
 * row 1 as well as row 0, so the row stride is pinned together with the field.</p>
 */
class GameSnapshotOrientationTest {

    // offsetof(Snapshot, npcs) / players / self and the v21 row geometry, as literals.
    private static final long NPC_COUNT = 576;
    private static final long NPCS = 580;
    private static final long NPC_STRIDE = 40;
    private static final long NPC_ORIENTATION = 36;
    private static final long PLAYER_COUNT = 41540;
    private static final long PLAYERS = 41544;
    private static final long PLAYER_STRIDE = 32;
    private static final long PLAYER_ORIENTATION = 28;
    private static final long OWN_INDEX = 12;
    private static final long SELF = 24;
    private static final long SELF_SERVER_INDEX = 0;
    private static final long SELF_ORIENTATION = 32;

    private static final int EAST = 12288;
    private static final int WEST = 4096;
    private static final int JUST_SHORT_OF_NORTH = Orientation.NORTH_RAW - 1;
    private static final int OUT_OF_CONTRACT = 0x4000;
    private static final int OWN_SERVER_INDEX = 7;

    @Test
    void npcRows_decodeFacingAtTheV21Offset_andTheSentinelAsUnknown() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(Layout.SNAPSHOT_SIZE, Long.BYTES);
            seg.set(ValueLayout.JAVA_INT, NPC_COUNT, 2);
            writeU16(seg, NPCS + NPC_ORIENTATION, Orientation.WIRE_UNKNOWN);
            writeU16(seg, NPCS + NPC_STRIDE + NPC_ORIENTATION, EAST);

            GameSnapshot snap = new GameSnapshotImpl(new SnapshotView(seg));

            assertFalse(snap.npcs().at(0).orientation().isKnown());
            assertEquals(Optional.of(Direction.EAST), snap.npcs().at(1).orientation().compass());
        }
    }

    @Test
    void playerRows_decodeFacingAtTheV21Offset() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(Layout.SNAPSHOT_SIZE, Long.BYTES);
            seg.set(ValueLayout.JAVA_INT, PLAYER_COUNT, 2);
            writeU16(seg, PLAYERS + PLAYER_ORIENTATION, WEST);
            writeU16(seg, PLAYERS + PLAYER_STRIDE + PLAYER_ORIENTATION, JUST_SHORT_OF_NORTH);

            GameSnapshot snap = new GameSnapshotImpl(new SnapshotView(seg));

            assertEquals(WEST, snap.players().at(0).orientation().raw());
            Orientation second = snap.players().at(1).orientation();
            assertEquals(JUST_SHORT_OF_NORTH, second.raw());
            assertTrue(second.isSameFacingAs(new Orientation(Orientation.NORTH_RAW)),
                    "a read-back one unit short of the set angle is the same facing");
        }
    }

    @Test
    void self_decodesFacingFromTheReusedPad() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(Layout.SNAPSHOT_SIZE, Long.BYTES);
            seg.set(ValueLayout.JAVA_INT, OWN_INDEX, OWN_SERVER_INDEX);
            seg.set(ValueLayout.JAVA_INT, SELF + SELF_SERVER_INDEX, OWN_SERVER_INDEX);
            writeU16(seg, SELF + SELF_ORIENTATION, WEST);

            GameSnapshot snap = new GameSnapshotImpl(new SnapshotView(seg));

            assertEquals(Optional.of(Direction.WEST), snap.self().orientation().compass());
        }
    }

    /** Degrade at the wire: a bad row decodes as unknown, and the session reports it once. */
    @Test
    void outOfContractFacing_decodesAsUnknown_andIsReportedOncePerDecoder() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(Layout.SNAPSHOT_SIZE, Long.BYTES);
            seg.set(ValueLayout.JAVA_INT, NPC_COUNT, 2);
            writeU16(seg, NPCS + NPC_ORIENTATION, OUT_OF_CONTRACT);
            writeU16(seg, NPCS + NPC_STRIDE + NPC_ORIENTATION, OUT_OF_CONTRACT);
            List<Integer> reported = new ArrayList<>();
            OrientationWireDecoder session = new OrientationWireDecoder(reported::add);

            GameSnapshot first = new GameSnapshotImpl(new SnapshotView(seg, session));
            GameSnapshot second = new GameSnapshotImpl(new SnapshotView(seg, session));

            assertFalse(first.npcs().at(0).orientation().isKnown());
            assertFalse(first.npcs().at(1).orientation().isKnown());
            assertFalse(second.npcs().at(0).orientation().isKnown());
            assertEquals(List.of(OUT_OF_CONTRACT), reported);
        }
    }

    private static void writeU16(MemorySegment seg, long offset, int value) {
        seg.set(ValueLayout.JAVA_SHORT, offset, (short) value);
    }
}
