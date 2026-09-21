package com.botwithus.bot.core.shm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the wire numbers that the producer's {@code static_assert} chain pins on
 * the C++ side. Every expected value here is a <b>literal</b>, deliberately not
 * recomputed from {@link Layout}'s own formulae: {@link Layout} derives its tail
 * offsets as a running sum of the caps above them, so two simultaneous edits
 * (say, shrinking one cap while growing another) can cancel out and leave the
 * derived offsets self-consistent but wrong. Literals cannot cancel — if the
 * host's idea of the layout moves, these disagree.
 *
 * <p>These are the numbers to diff against NXTLibrary's SharedLayout.h:
 * {@code kProtocolVersion}, {@code offsetof(Snapshot, dynRegion)} and
 * {@code sizeof(Snapshot)}. If a change makes a test here fail, the fix is
 * either to change both sides or to change neither — never to update the
 * literal so the build goes green.</p>
 */
class LayoutWireOffsetsTest {

    @Test
    void protocolVersionIsTwentyOne() {
        assertEquals(21, Layout.PROTOCOL_VERSION,
                "PROTOCOL_VERSION must equal kProtocolVersion in SharedLayout.h");
    }

    /**
     * v20 widened {@code LocationEntry} from 20 to 24 bytes, which is the whole reason every
     * offset below moved. Pinning the stride and the new field separately means a future edit
     * that changes one without the other cannot pass by cancelling out.
     */
    @Test
    void locationEntryIsTwentyFourBytesWithResolvedIdLast() {
        assertEquals(24, Layout.LOCATION_ENTRY_SIZE, "sizeof(ipc::LocationEntry)");
        assertEquals(20, Layout.LOC_RESOLVEDID_OFFSET, "offsetof(LocationEntry, resolvedId)");
        assertEquals(Layout.LOCATION_ENTRY_SIZE, Layout.LOC_RESOLVEDID_OFFSET + 4,
                "resolvedId is the last field; the row ends immediately after it");
    }

    /**
     * v21 appended a {@code u16 orientation} (plus a u16 pad) to NpcEntry and PlayerEntry and
     * gave LocalPlayer's old u32 pad to it, keeping LocalPlayer at 552.
     */
    @Test
    void entityRowsCarryOrientationAtTheV21Offsets() {
        assertEquals(40, Layout.NPC_ENTRY_SIZE, "sizeof(ipc::NpcEntry)");
        assertEquals(36, Layout.NPC_ORIENTATION_OFFSET, "offsetof(NpcEntry, orientation)");
        assertEquals(32, Layout.PLAYER_ENTRY_SIZE, "sizeof(ipc::PlayerEntry)");
        assertEquals(28, Layout.PLAYER_ORIENTATION_OFFSET, "offsetof(PlayerEntry, orientation)");
        assertEquals(552, Layout.LOCAL_PLAYER_SIZE, "sizeof(ipc::LocalPlayer)");
        assertEquals(32, Layout.LP_ORIENTATION_OFFSET, "offsetof(LocalPlayer, orientation)");
        assertEquals(41544, Layout.SNAP_PLAYERS_OFFSET, "offsetof(Snapshot, players)");
    }

    @Test
    void gameCycleMovedByExactlyTheEntityRowGrowth() {
        assertEquals(345220, Layout.SNAP_GAMECYCLE_OFFSET,
                "v21 widened the NPC and player rows; gameCycle shifts with everything after");
        assertEquals(332932 + (Layout.NPC_CAP + Layout.PLAYER_CAP) * 4,
                Layout.SNAP_GAMECYCLE_OFFSET,
                "the shift must be exactly 4 * (kNpcCap + kPlayerCap) -- the cost of orientation");
    }

    @Test
    void dynamicRegionBlockOffsetsArePinned() {
        assertEquals(345224, Layout.SNAP_DYNREGION_OFFSET, "offsetof(Snapshot, dynRegion)");
        assertEquals(345260, Layout.SNAP_DYNCHUNKCOUNT_OFFSET, "offsetof(Snapshot, dynChunkCount)");
        assertEquals(345264, Layout.SNAP_DYNCHUNKS_OFFSET, "offsetof(Snapshot, dynChunks)");
    }

    @Test
    void dynamicRegionHeaderIsThirtySixBytes() {
        assertEquals(36, Layout.DYNREGION_SIZE, "sizeof(ipc::DynamicRegion)");
        assertEquals(36, Layout.SNAP_DYNCHUNKCOUNT_OFFSET - Layout.SNAP_DYNREGION_OFFSET,
                "the header must occupy exactly the gap it claims");
    }

    /**
     * Every interior field of the header, pinned individually.
     *
     * <p>Pinning only the base offset and the total size leaves the interior
     * free to be reordered without either end noticing: the ten fields would
     * still sum to 36 bytes and still start at 300168. {@code GameSnapshotImplTest}
     * cannot catch it either, because its writers derive their offsets from the
     * same constants its readers do — swap two and it round-trips happily. These
     * literals are the only thing standing between a C++ field reorder and a host
     * that silently reads {@code gridH} as {@code gridW}.</p>
     */
    @Test
    void dynamicRegionInteriorFieldOffsetsArePinned() {
        assertEquals(0, Layout.DYNREGION_ISINSTANCE_OFFSET, "isInstance");
        assertEquals(1, Layout.DYNREGION_TRUNCATED_OFFSET, "truncated");
        // bytes 2..3 are _pad0
        assertEquals(4, Layout.DYNREGION_SCENEMODE_OFFSET, "sceneMode");
        assertEquals(8, Layout.DYNREGION_ORIGINMAPX_OFFSET, "originMapX");
        assertEquals(12, Layout.DYNREGION_ORIGINMAPY_OFFSET, "originMapY");
        assertEquals(16, Layout.DYNREGION_MAXMAPX_OFFSET, "maxMapX");
        assertEquals(20, Layout.DYNREGION_MAXMAPY_OFFSET, "maxMapY");
        assertEquals(24, Layout.DYNREGION_GRIDW_OFFSET, "gridW");
        assertEquals(28, Layout.DYNREGION_GRIDH_OFFSET, "gridH");
        assertEquals(32, Layout.DYNREGION_REQUIREDCHUNKS_OFFSET, "requiredChunks");
    }

    @Test
    void dynChunkCapIsPinned() {
        assertEquals(16384, Layout.DYN_CHUNK_CAP, "kDynChunkCap");
    }

    @Test
    void snapshotSizeIsPinned() {
        assertEquals(410800, Layout.SNAPSHOT_SIZE, "sizeof(Snapshot)");
    }

    /**
     * The producer's {@code Snapshot} contains 8-aligned members, so its size
     * must stay a multiple of 8 or the two snapshot buffers in the mapping stop
     * being 8-aligned relative to each other. v18 named a tail pad specifically
     * to keep this true; v19's block must not undo it.
     */
    @Test
    void snapshotSizeStaysEightAligned() {
        assertTrue(Layout.SNAPSHOT_SIZE % 8 == 0,
                "SNAPSHOT_SIZE must be a multiple of 8; got " + Layout.SNAPSHOT_SIZE);
    }
}
