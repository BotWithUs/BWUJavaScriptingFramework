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
    void protocolVersionIsTwentyThree() {
        assertEquals(23, Layout.PROTOCOL_VERSION,
                "PROTOCOL_VERSION must equal kProtocolVersion in SharedLayout.h");
    }

    /**
     * v22 raised the open-interface cap from 64 to 256 and put {@code openIfaceTotal}
     * between the count and the array, with a u32 pad after the array. Each field is
     * pinned on its own so a reorder inside the block cannot pass by keeping the sum.
     */
    @Test
    void openInterfaceBlockIsPinned() {
        assertEquals(256, Layout.OPEN_IFACE_CAP, "kOpenIfaceCap");
        assertEquals(320376, Layout.SNAP_OPENIFACECOUNT_OFFSET, "offsetof(Snapshot, openIfaceCount)");
        assertEquals(320380, Layout.SNAP_OPENIFACETOTAL_OFFSET, "offsetof(Snapshot, openIfaceTotal)");
        assertEquals(320384, Layout.SNAP_OPENIFACES_OFFSET, "offsetof(Snapshot, openIfaces)");
        assertEquals(321408, Layout.SNAP_OPENIFACEFLAGS_OFFSET, "offsetof(Snapshot, openIfaceFlags)");
        assertEquals(321664, Layout.SNAP_OPENIFACEPAD_OFFSET,
                "offsetof(Snapshot, _padAfterOpenIfaces)");
        assertEquals(321668, Layout.SNAP_GROUNDITEMCOUNT_OFFSET, "offsetof(Snapshot, groundItemCount)");
        assertEquals(321672, Layout.SNAP_GROUNDITEMS_OFFSET, "offsetof(Snapshot, groundItems)");
    }

    /**
     * v23 put one flags byte per open-interface slot between the id array and the pad. The
     * array must be exactly the cap long, so a flag can never sit at a different index from
     * the id it describes.
     */
    @Test
    void openInterfaceFlagsAreIndexParallelToTheIds() {
        assertEquals(Layout.SNAP_OPENIFACES_OFFSET + Layout.OPEN_IFACE_CAP * Integer.BYTES,
                Layout.SNAP_OPENIFACEFLAGS_OFFSET, "flags start where the id array ends");
        assertEquals(Layout.OPEN_IFACE_CAP,
                Layout.SNAP_OPENIFACEPAD_OFFSET - Layout.SNAP_OPENIFACEFLAGS_OFFSET,
                "one flags byte per id slot");
    }

    @Test
    void projectileBlockIsPinned() {
        assertEquals(338056, Layout.SNAP_PROJECTILECOUNT_OFFSET, "offsetof(Snapshot, projectileCount)");
        assertEquals(338060, Layout.SNAP_PROJECTILES_OFFSET, "offsetof(Snapshot, projectiles)");
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
    void gameCycleMovedByExactlyTheOpenInterfaceGrowth() {
        assertEquals(346252, Layout.SNAP_GAMECYCLE_OFFSET,
                "v23 grew the open-interface block; gameCycle shifts with everything after");
        assertEquals(345996 + Layout.OPEN_IFACE_CAP, Layout.SNAP_GAMECYCLE_OFFSET,
                "the v23 shift must be exactly one flags byte per slot = 256");
    }

    @Test
    void dynamicRegionBlockOffsetsArePinned() {
        assertEquals(346256, Layout.SNAP_DYNREGION_OFFSET, "offsetof(Snapshot, dynRegion)");
        assertEquals(346292, Layout.SNAP_DYNCHUNKCOUNT_OFFSET, "offsetof(Snapshot, dynChunkCount)");
        assertEquals(346296, Layout.SNAP_DYNCHUNKS_OFFSET, "offsetof(Snapshot, dynChunks)");
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
        assertEquals(411832, Layout.SNAPSHOT_SIZE, "sizeof(Snapshot)");
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
