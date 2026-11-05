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
    void protocolVersionIsTwentyFive() {
        assertEquals(25, Layout.PROTOCOL_VERSION,
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
        assertEquals(377736, Layout.SNAP_OPENIFACECOUNT_OFFSET, "offsetof(Snapshot, openIfaceCount)");
        assertEquals(377740, Layout.SNAP_OPENIFACETOTAL_OFFSET, "offsetof(Snapshot, openIfaceTotal)");
        assertEquals(377744, Layout.SNAP_OPENIFACES_OFFSET, "offsetof(Snapshot, openIfaces)");
        assertEquals(378768, Layout.SNAP_OPENIFACEFLAGS_OFFSET, "offsetof(Snapshot, openIfaceFlags)");
        assertEquals(379024, Layout.SNAP_OPENIFACEPAD_OFFSET,
                "offsetof(Snapshot, _padAfterOpenIfaces)");
        assertEquals(379028, Layout.SNAP_GROUNDITEMCOUNT_OFFSET, "offsetof(Snapshot, groundItemCount)");
        assertEquals(379032, Layout.SNAP_GROUNDITEMS_OFFSET, "offsetof(Snapshot, groundItems)");
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
        assertEquals(395416, Layout.SNAP_PROJECTILECOUNT_OFFSET, "offsetof(Snapshot, projectileCount)");
        assertEquals(395420, Layout.SNAP_PROJECTILES_OFFSET, "offsetof(Snapshot, projectiles)");
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
     * gave LocalPlayer's old u32 pad to it. v24 left those fields where they were and grew the
     * rows behind them, so they are pinned here beside the new geometry.
     */
    @Test
    void entityRowsKeepOrientationAtTheV21Offsets() {
        assertEquals(36, Layout.NPC_ORIENTATION_OFFSET, "offsetof(NpcEntry, orientation)");
        assertEquals(28, Layout.PLAYER_ORIENTATION_OFFSET, "offsetof(PlayerEntry, orientation)");
        assertEquals(32, Layout.LP_ORIENTATION_OFFSET, "offsetof(LocalPlayer, orientation)");
    }

    /** v24: NpcEntry 40 to 48 with activeTypeId at +40 and combatLevel at +44. */
    @Test
    void npcEntryCarriesActiveTypeAndCombatLevel() {
        assertEquals(48, Layout.NPC_ENTRY_SIZE, "sizeof(ipc::NpcEntry)");
        assertEquals(4, Layout.NPC_TYPEID_OFFSET, "offsetof(NpcEntry, typeId) is still the base id");
        assertEquals(40, Layout.NPC_ACTIVETYPEID_OFFSET, "offsetof(NpcEntry, activeTypeId)");
        assertEquals(44, Layout.NPC_COMBATLEVEL_OFFSET, "offsetof(NpcEntry, combatLevel)");
    }

    /** v24 put a 16-byte name at +32 and the truncation bit in flags; v25 left them there. */
    @Test
    void playerEntryCarriesTheName() {
        assertEquals(32, Layout.PLAYER_NAME_OFFSET, "offsetof(PlayerEntry, name)");
        assertEquals(16, Layout.PLAYER_NAME_CAP, "kPlayerNameCap");
        assertEquals(Layout.PLAYER_SERVERTILEX_OFFSET, Layout.PLAYER_NAME_OFFSET + Layout.PLAYER_NAME_CAP,
                "the server tile starts where the name ends");
        assertEquals(2, Layout.FLAG_NAME_TRUNCATED, "kFlagNameTruncated = 1u << 1");
    }

    /** v25: PlayerEntry 48 to 56, with the server tile at +48 and 3 pad bytes ending the row. */
    @Test
    void playerEntryCarriesTheServerTile() {
        assertEquals(56, Layout.PLAYER_ENTRY_SIZE, "sizeof(ipc::PlayerEntry)");
        assertEquals(48, Layout.PLAYER_SERVERTILEX_OFFSET, "offsetof(PlayerEntry, serverTileX)");
        assertEquals(50, Layout.PLAYER_SERVERTILEY_OFFSET, "offsetof(PlayerEntry, serverTileY)");
        assertEquals(52, Layout.PLAYER_SERVERPLANE_OFFSET, "offsetof(PlayerEntry, serverPlane)");
    }

    /** v24 appended runEnergy and runWeight after the skills; v25 left them there. */
    @Test
    void localPlayerCarriesRunState() {
        assertEquals(552, Layout.LP_RUNENERGY_OFFSET, "offsetof(LocalPlayer, runEnergy)");
        assertEquals(556, Layout.LP_RUNWEIGHT_OFFSET, "offsetof(LocalPlayer, runWeight)");
    }

    /** v25: LocalPlayer 560 to 568, with the server tile appended after the run state. */
    @Test
    void localPlayerCarriesTheServerTile() {
        assertEquals(568, Layout.LOCAL_PLAYER_SIZE, "sizeof(ipc::LocalPlayer)");
        assertEquals(560, Layout.LP_SERVERTILEX_OFFSET, "offsetof(LocalPlayer, serverTileX)");
        assertEquals(562, Layout.LP_SERVERTILEY_OFFSET, "offsetof(LocalPlayer, serverTileY)");
        assertEquals(564, Layout.LP_SERVERPLANE_OFFSET, "offsetof(LocalPlayer, serverPlane)");
    }

    @Test
    void entityArraysAreAtTheV25Offsets() {
        assertEquals(592, Layout.SNAP_NPCCOUNT_OFFSET, "offsetof(Snapshot, npcCount)");
        assertEquals(596, Layout.SNAP_NPCS_OFFSET, "offsetof(Snapshot, npcs)");
        assertEquals(49748, Layout.SNAP_PLAYERCOUNT_OFFSET, "offsetof(Snapshot, playerCount)");
        assertEquals(49752, Layout.SNAP_PLAYERS_OFFSET, "offsetof(Snapshot, players)");
        assertEquals(164440, Layout.SNAP_LOCATIONCOUNT_OFFSET, "offsetof(Snapshot, locationCount)");
        assertEquals(164444, Layout.SNAP_LOCATIONS_OFFSET, "offsetof(Snapshot, locations)");
        assertEquals(361056, Layout.SNAP_INVENTORYCOUNT_OFFSET, "offsetof(Snapshot, inventoryCount)");
        assertEquals(361316, Layout.SNAP_INVITEMCOUNT_OFFSET, "offsetof(Snapshot, invItemCount)");
        assertEquals(377704, Layout.SNAP_PRODUCER_OFFSET, "offsetof(Snapshot, producer)");
    }

    @Test
    void gameCycleMovedByExactlyTheRowGrowth() {
        assertEquals(403612, Layout.SNAP_GAMECYCLE_OFFSET, "offsetof(Snapshot, gameCycle)");
        assertEquals(387220 + 16392, Layout.SNAP_GAMECYCLE_OFFSET,
                "v25 moves every field from locationCount on by 8 + 8 * 2048 = 16392");
    }

    @Test
    void dynamicRegionBlockOffsetsArePinned() {
        assertEquals(403616, Layout.SNAP_DYNREGION_OFFSET, "offsetof(Snapshot, dynRegion)");
        assertEquals(403652, Layout.SNAP_DYNCHUNKCOUNT_OFFSET, "offsetof(Snapshot, dynChunkCount)");
        assertEquals(403656, Layout.SNAP_DYNCHUNKS_OFFSET, "offsetof(Snapshot, dynChunks)");
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
        assertEquals(469192, Layout.SNAPSHOT_SIZE, "sizeof(Snapshot)");
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
