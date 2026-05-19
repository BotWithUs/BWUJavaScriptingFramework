package com.botwithus.bot.core.impl.snapshot;

import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.Inventory;
import com.botwithus.bot.api.snapshot.InventoryItem;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Location;
import com.botwithus.bot.api.snapshot.LocationFilter;
import com.botwithus.bot.api.snapshot.Npc;
import com.botwithus.bot.api.snapshot.NpcFilter;
import com.botwithus.bot.api.snapshot.Player;
import com.botwithus.bot.api.snapshot.PlayerFilter;
import com.botwithus.bot.api.snapshot.Skill;
import com.botwithus.bot.core.shm.Layout;
import com.botwithus.bot.core.shm.SnapshotView;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic-buffer tests: allocate a snapshot-sized {@link MemorySegment},
 * write the same byte layout the C++ producer publishes, then exercise
 * {@link GameSnapshotImpl} through the public {@link GameSnapshot} surface.
 *
 * <p>Each test owns its own arena so failures stay isolated.</p>
 */
class GameSnapshotImplTest {

    // ------------------------------------------------------------------
    // Metadata
    // ------------------------------------------------------------------

    @Test
    void tickMetadataPassesThrough() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = allocSnapshot(arena);
            seg.set(ValueLayout.JAVA_LONG, Layout.SNAP_TICKID_OFFSET, 12_345L);
            seg.set(ValueLayout.JAVA_INT, Layout.SNAP_GAMESTATE_OFFSET, 30);
            seg.set(ValueLayout.JAVA_INT, Layout.SNAP_OWNINDEX_OFFSET, 7);

            GameSnapshot snap = build(seg);

            assertEquals(12_345L, snap.tickId());
            assertEquals(30, snap.gameState());
            assertEquals(7, snap.ownIndex());
        }
    }

    // ------------------------------------------------------------------
    // self()
    // ------------------------------------------------------------------

    @Test
    void selfNullWhenNotInGame() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = allocSnapshot(arena);
            seg.set(ValueLayout.JAVA_INT, Layout.SNAP_OWNINDEX_OFFSET, -1);

            assertNull(build(seg).self());
        }
    }

    @Test
    void selfNullWhenSelfServerIndexNegative() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = allocSnapshot(arena);
            seg.set(ValueLayout.JAVA_INT, Layout.SNAP_OWNINDEX_OFFSET, 5);
            writeLpInt(seg, Layout.LP_SERVERINDEX_OFFSET, -1);

            assertNull(build(seg).self());
        }
    }

    @Test
    void selfPopulatedWithSkills() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = allocSnapshot(arena);
            seg.set(ValueLayout.JAVA_INT, Layout.SNAP_OWNINDEX_OFFSET, 7);

            writeLpInt(seg, Layout.LP_SERVERINDEX_OFFSET, 7);
            writeLpInt(seg, Layout.LP_COMBATLEVEL_OFFSET, 138);
            writeLpShort(seg, Layout.LP_TILEX_OFFSET, (short) 3200);
            writeLpShort(seg, Layout.LP_TILEY_OFFSET, (short) 3201);
            writeLpByte(seg, Layout.LP_PLANE_OFFSET, (byte) 0);
            writeLpByte(seg, Layout.LP_FLAGS_OFFSET, (byte) 1);
            writeLpShort(seg, Layout.LP_FOLLOWINGINDEX_OFFSET, (short) -1);
            writeLpInt(seg, Layout.LP_ANIMATIONID_OFFSET, 100);
            writeLpInt(seg, Layout.LP_STANCEID_OFFSET, 200);
            writeLpShort(seg, Layout.LP_TARGETINDEX_OFFSET, (short) 42);
            writeLpByte(seg, Layout.LP_TARGETTYPE_OFFSET, (byte) 1);
            writeLpByte(seg, Layout.LP_ISMEMBER_OFFSET, (byte) 1);
            writeLpInt(seg, Layout.LP_SKILLCOUNT_OFFSET, 2);
            writeSkill(seg, 0, 1, 13_034_431, 99, 105);
            writeSkill(seg, 1, 4, 100_000, 50, 50);

            LocalPlayer self = build(seg).self();

            assertNotNull(self);
            assertEquals(7, self.serverIndex());
            assertEquals(138, self.combatLevel());
            assertEquals(3200, self.tileX());
            assertEquals(3201, self.tileY());
            assertEquals(0, self.plane());
            assertEquals(1, self.flags());
            assertTrue(self.isMoving());
            assertEquals(-1, self.followingIndex());
            assertEquals(100, self.animationId());
            assertEquals(200, self.stanceId());
            assertEquals(42, self.targetIndex());
            assertEquals(1, self.targetType());
            assertTrue(self.isMember());
            assertEquals(List.of(
                    new Skill(1, 13_034_431, 99, 105),
                    new Skill(4, 100_000, 50, 50)), self.skills());
        }
    }

    // ------------------------------------------------------------------
    // npcs()
    // ------------------------------------------------------------------

    @Test
    void npcsEmptyByDefault() {
        try (Arena arena = Arena.ofConfined()) {
            GameSnapshot.Npcs npcs = build(allocSnapshot(arena)).npcs();
            assertEquals(0, npcs.count());
            assertEquals(0L, npcs.stream().count());
            assertTrue(npcs.byServerIndex(42).isEmpty());
            assertThrows(IndexOutOfBoundsException.class, () -> npcs.at(0));
        }
    }

    @Test
    void npcsAtBuildsRecord() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = allocSnapshot(arena);
            seg.set(ValueLayout.JAVA_INT, Layout.SNAP_NPCCOUNT_OFFSET, 1);
            writeNpc(seg, 0, /* serverIndex */ 42, /* typeId */ 1234,
                    /* tileX */ (short) 3300, /* tileY */ (short) 3400,
                    /* plane */ (byte) 1, /* flags */ (byte) 1,
                    /* followingIndex */ (short) -1, /* animationId */ 7,
                    /* stanceId */ 0, /* hp */ 800, /* maxHp */ 1000);

            Npc npc = build(seg).npcs().at(0);

            assertEquals(42, npc.serverIndex());
            assertEquals(1234, npc.typeId());
            assertEquals(3300, npc.tileX());
            assertEquals(3400, npc.tileY());
            assertEquals(1, npc.plane());
            assertEquals(1, npc.flags());
            assertTrue(npc.isMoving());
            assertEquals(-1, npc.followingIndex());
            assertEquals(7, npc.animationId());
            assertEquals(0, npc.stanceId());
            assertEquals(800, npc.hp());
            assertEquals(1000, npc.maxHp());
        }
    }

    @Test
    void npcsByServerIndexLocatesEntry() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = allocSnapshot(arena);
            seg.set(ValueLayout.JAVA_INT, Layout.SNAP_NPCCOUNT_OFFSET, 3);
            writeNpc(seg, 0, 10, 100, (short) 0, (short) 0, (byte) 0, (byte) 0,
                    (short) -1, -1, 0, 0, 0);
            writeNpc(seg, 1, 20, 200, (short) 0, (short) 0, (byte) 0, (byte) 0,
                    (short) -1, -1, 0, 0, 0);
            writeNpc(seg, 2, 30, 300, (short) 0, (short) 0, (byte) 0, (byte) 0,
                    (short) -1, -1, 0, 0, 0);

            GameSnapshot.Npcs npcs = build(seg).npcs();

            assertEquals(20, npcs.byServerIndex(20).orElseThrow().serverIndex());
            assertEquals(200, npcs.byServerIndex(20).orElseThrow().typeId());
            assertTrue(npcs.byServerIndex(99).isEmpty());
        }
    }

    @Test
    void npcsFilterByTypeAndMoving() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = allocSnapshot(arena);
            seg.set(ValueLayout.JAVA_INT, Layout.SNAP_NPCCOUNT_OFFSET, 4);
            writeNpc(seg, 0, 1, 100, (short) 0, (short) 0, (byte) 0, /* moving */ (byte) 1,
                    (short) -1, -1, 0, 100, 100);
            writeNpc(seg, 1, 2, 100, (short) 0, (short) 0, (byte) 0, /* still  */ (byte) 0,
                    (short) -1, -1, 0, 100, 100);
            writeNpc(seg, 2, 3, 200, (short) 0, (short) 0, (byte) 0, /* moving */ (byte) 1,
                    (short) -1, -1, 0,  50, 100);
            writeNpc(seg, 3, 4, 100, (short) 0, (short) 0, (byte) 0, /* still  */ (byte) 0,
                    (short) -1, -1, 0,   0, 100);

            GameSnapshot.Npcs npcs = build(seg).npcs();

            List<Npc> ofType100 = npcs.filter(NpcFilter.typeId(100));
            assertEquals(3, ofType100.size());

            List<Npc> moving = npcs.filter(NpcFilter.moving());
            assertEquals(2, moving.size());

            List<Npc> alive = npcs.filter(NpcFilter.alive());
            assertEquals(3, alive.size());

            List<Npc> movingType100 = npcs.filter(NpcFilter.typeId(100).and(NpcFilter.moving()));
            assertEquals(1, movingType100.size());
            assertEquals(1, movingType100.get(0).serverIndex());
        }
    }

    @Test
    void npcsStreamYieldsAllInOrder() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = allocSnapshot(arena);
            seg.set(ValueLayout.JAVA_INT, Layout.SNAP_NPCCOUNT_OFFSET, 3);
            writeNpc(seg, 0, 11, 0, (short) 0, (short) 0, (byte) 0, (byte) 0,
                    (short) -1, -1, 0, 0, 0);
            writeNpc(seg, 1, 22, 0, (short) 0, (short) 0, (byte) 0, (byte) 0,
                    (short) -1, -1, 0, 0, 0);
            writeNpc(seg, 2, 33, 0, (short) 0, (short) 0, (byte) 0, (byte) 0,
                    (short) -1, -1, 0, 0, 0);

            int[] indices = build(seg).npcs().stream().mapToInt(Npc::serverIndex).toArray();
            assertArrayEqualsBoxed(new int[]{11, 22, 33}, indices);
        }
    }

    // ------------------------------------------------------------------
    // players()
    // ------------------------------------------------------------------

    @Test
    void playersAtAndFilters() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = allocSnapshot(arena);
            seg.set(ValueLayout.JAVA_INT, Layout.SNAP_PLAYERCOUNT_OFFSET, 3);
            writePlayer(seg, 0, /* serverIndex */ 1,
                    (short) 100, (short) 200, (byte) 0, /* moving */ (byte) 1,
                    (short) -1, 5, 0, /* combatLevel */ 50);
            writePlayer(seg, 1, 2,
                    (short) 101, (short) 200, (byte) 0, (byte) 0,
                    (short) -1, 5, 0, 75);
            writePlayer(seg, 2, 3,
                    (short) 102, (short) 200, (byte) 1, (byte) 0,
                    (short) -1, 5, 0, 138);

            GameSnapshot.Players players = build(seg).players();
            assertEquals(3, players.count());

            Player at1 = players.at(1);
            assertEquals(2, at1.serverIndex());
            assertEquals(101, at1.tileX());
            assertEquals(75, at1.combatLevel());
            assertFalse(at1.isMoving());

            assertTrue(players.byServerIndex(3).isPresent());
            assertEquals(138, players.byServerIndex(3).orElseThrow().combatLevel());

            List<Player> mid = players.filter(PlayerFilter.combatLevelBetween(60, 100));
            assertEquals(1, mid.size());
            assertEquals(2, mid.get(0).serverIndex());

            List<Player> onPlane1 = players.filter(PlayerFilter.onPlane(1));
            assertEquals(1, onPlane1.size());
            assertEquals(3, onPlane1.get(0).serverIndex());
        }
    }

    // ------------------------------------------------------------------
    // locations()
    // ------------------------------------------------------------------

    @Test
    void locationsEmptyByDefault() {
        try (Arena arena = Arena.ofConfined()) {
            GameSnapshot.Locations locs = build(allocSnapshot(arena)).locations();
            assertEquals(0, locs.count());
            assertEquals(0L, locs.stream().count());
            assertThrows(IndexOutOfBoundsException.class, () -> locs.at(0));
        }
    }

    @Test
    void locationsAtBuildsRecordAndFlagsRoundTrip() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = allocSnapshot(arena);
            seg.set(ValueLayout.JAVA_INT, Layout.SNAP_LOCATIONCOUNT_OFFSET, 2);
            writeLocation(seg, 0, /* typeId */ 38732, /* interactId */ 7,
                    /* animationId */ -1, (short) 3200, (short) 3300,
                    /* plane */ (byte) 0, /* shape */ 10, /* rotation */ 1,
                    /* flags */ Layout.LOC_FLAG_HIDDEN);
            writeLocation(seg, 1, 123, -1, 456, (short) 3201, (short) 3300,
                    (byte) 1, 22, 3, Layout.LOC_FLAG_COMBINED_SECTION);

            GameSnapshot.Locations locs = build(seg).locations();
            assertEquals(2, locs.count());

            Location l0 = locs.at(0);
            assertEquals(38732, l0.typeId());
            assertEquals(7, l0.interactId());
            assertEquals(-1, l0.animationId());
            assertEquals(3200, l0.tileX());
            assertEquals(3300, l0.tileY());
            assertEquals(0, l0.plane());
            assertEquals(10, l0.shape());
            assertEquals(1, l0.rotation());
            assertTrue(l0.isHidden());
            assertFalse(l0.isCombinedSection());

            Location l1 = locs.at(1);
            assertTrue(l1.isCombinedSection());
            assertEquals(456, l1.animationId());

            List<Location> sections = locs.filter(LocationFilter.combinedSection());
            assertEquals(1, sections.size());
            assertEquals(123, sections.get(0).typeId());

            List<Location> animating = locs.filter(LocationFilter.animating());
            assertEquals(1, animating.size());
            assertEquals(456, animating.get(0).animationId());
        }
    }

    @Test
    void sceneVersionRoundTrips() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = allocSnapshot(arena);
            seg.set(ValueLayout.JAVA_INT,
                    Layout.SNAP_PRODUCER_OFFSET + Layout.PRODUCER_SCENEVERSION_OFFSET,
                    42);
            assertEquals(42, build(seg).sceneVersion());
        }
    }

    // ------------------------------------------------------------------
    // inventories()
    // ------------------------------------------------------------------

    @Test
    void inventoriesEmptyByDefault() {
        try (Arena arena = Arena.ofConfined()) {
            GameSnapshot.Inventories inv = build(allocSnapshot(arena)).inventories();
            assertEquals(0, inv.count());
            assertTrue(inv.byInvId(93).isEmpty());
        }
    }

    @Test
    void inventoriesBuildItemsFromFlatBuffer() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = allocSnapshot(arena);

            // Two inventories: backpack (id 93, 28 slots, items 0..27) and
            // worn equipment (id 94, 12 slots, items 28..39). Use a sparse
            // layout: only some slots have items, rest get itemId == -1
            // signaled by the producer.
            seg.set(ValueLayout.JAVA_INT, Layout.SNAP_INVENTORYCOUNT_OFFSET, 2);
            writeInvHeader(seg, 0, 93, (short) 28, (short) 0);
            writeInvHeader(seg, 1, 94, (short) 12, (short) 28);

            seg.set(ValueLayout.JAVA_INT, Layout.SNAP_INVITEMCOUNT_OFFSET, 40);
            // Backpack: slot 0 = item 1234 x 5; slot 1 = empty(-1); slot 2 = item 9999 x 1
            writeFlatItem(seg, 0, 1234, 5);
            writeFlatItem(seg, 1, -1, 0);
            writeFlatItem(seg, 2, 9999, 1);
            for (int i = 3; i < 28; i++) {
                writeFlatItem(seg, i, -1, 0);
            }
            // Worn: slot 0 = item 4151 x 1; rest empty
            writeFlatItem(seg, 28, 4151, 1);
            for (int i = 29; i < 40; i++) {
                writeFlatItem(seg, i, -1, 0);
            }

            GameSnapshot.Inventories inv = build(seg).inventories();
            assertEquals(2, inv.count());

            Inventory backpack = inv.byInvId(93).orElseThrow();
            assertEquals(28, backpack.slotCount());
            assertEquals(28, backpack.items().size());
            InventoryItem slot0 = backpack.items().get(0);
            assertEquals(1234, slot0.itemId());
            assertEquals(5, slot0.quantity());
            assertFalse(slot0.isEmpty());
            assertTrue(backpack.items().get(1).isEmpty());
            assertEquals(9999, backpack.items().get(2).itemId());

            Inventory worn = inv.byInvId(94).orElseThrow();
            assertEquals(12, worn.items().size());
            assertEquals(4151, worn.items().get(0).itemId());
            assertTrue(worn.items().get(11).isEmpty());

            assertTrue(inv.byInvId(999).isEmpty());
        }
    }

    @Test
    void inventoryItemsBeyondFlatBufferReadAsEmpty() {
        // If the producer publishes a slotCount + firstItemIdx that walks
        // past invItemCount (e.g. mid-flip race), the impl should fill the
        // overrun with empty slots rather than throw IOOBE — keeps script
        // code stable across torn reads.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = allocSnapshot(arena);
            seg.set(ValueLayout.JAVA_INT, Layout.SNAP_INVENTORYCOUNT_OFFSET, 1);
            writeInvHeader(seg, 0, 93, /* slotCount */ (short) 4, /* firstItemIdx */ (short) 0);
            seg.set(ValueLayout.JAVA_INT, Layout.SNAP_INVITEMCOUNT_OFFSET, 2);
            writeFlatItem(seg, 0, 100, 1);
            writeFlatItem(seg, 1, 200, 2);

            Inventory backpack = build(seg).inventories().at(0);
            assertEquals(4, backpack.items().size());
            assertEquals(100, backpack.items().get(0).itemId());
            assertEquals(200, backpack.items().get(1).itemId());
            assertTrue(backpack.items().get(2).isEmpty());
            assertTrue(backpack.items().get(3).isEmpty());
        }
    }

    // ------------------------------------------------------------------
    // Defensive copies
    // ------------------------------------------------------------------

    @Test
    void localPlayerSkillsListIsImmutable() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = allocSnapshot(arena);
            seg.set(ValueLayout.JAVA_INT, Layout.SNAP_OWNINDEX_OFFSET, 7);
            writeLpInt(seg, Layout.LP_SERVERINDEX_OFFSET, 7);
            writeLpInt(seg, Layout.LP_SKILLCOUNT_OFFSET, 1);
            writeSkill(seg, 0, 1, 0, 1, 1);

            LocalPlayer self = build(seg).self();
            assertSame(self.skills(), self.skills(), "same list both calls");
            assertThrows(UnsupportedOperationException.class,
                    () -> self.skills().add(new Skill(2, 0, 1, 1)));
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static MemorySegment allocSnapshot(Arena arena) {
        return arena.allocate(Layout.SNAPSHOT_SIZE, 8);
    }

    private static GameSnapshotImpl build(MemorySegment seg) {
        return new GameSnapshotImpl(new SnapshotView(seg));
    }

    private static void writeLpInt(MemorySegment seg, int fieldOffset, int value) {
        seg.set(ValueLayout.JAVA_INT, Layout.SNAP_SELF_OFFSET + fieldOffset, value);
    }

    private static void writeLpShort(MemorySegment seg, int fieldOffset, short value) {
        seg.set(ValueLayout.JAVA_SHORT, Layout.SNAP_SELF_OFFSET + fieldOffset, value);
    }

    private static void writeLpByte(MemorySegment seg, int fieldOffset, byte value) {
        seg.set(ValueLayout.JAVA_BYTE, Layout.SNAP_SELF_OFFSET + fieldOffset, value);
    }

    private static void writeSkill(MemorySegment seg, int slot, int typeId,
                                   int experience, int actualLevel, int boostedLevel) {
        long base = Layout.SNAP_SELF_OFFSET + Layout.LP_SKILLS_OFFSET
                + (long) slot * Layout.SKILL_ENTRY_SIZE;
        seg.set(ValueLayout.JAVA_INT, base + Layout.SKILL_TYPEID_OFFSET, typeId);
        seg.set(ValueLayout.JAVA_INT, base + Layout.SKILL_EXPERIENCE_OFFSET, experience);
        seg.set(ValueLayout.JAVA_INT, base + Layout.SKILL_ACTUALLEVEL_OFFSET, actualLevel);
        seg.set(ValueLayout.JAVA_INT, base + Layout.SKILL_BOOSTEDLEVEL_OFFSET, boostedLevel);
    }

    private static void writeNpc(MemorySegment seg, int index,
                                 int serverIndex, int typeId,
                                 short tileX, short tileY, byte plane, byte flags,
                                 short followingIndex, int animationId, int stanceId,
                                 int hp, int maxHp) {
        long base = Layout.SNAP_NPCS_OFFSET + (long) index * Layout.NPC_ENTRY_SIZE;
        seg.set(ValueLayout.JAVA_INT,   base + Layout.NPC_SERVERINDEX_OFFSET,    serverIndex);
        seg.set(ValueLayout.JAVA_INT,   base + Layout.NPC_TYPEID_OFFSET,         typeId);
        seg.set(ValueLayout.JAVA_SHORT, base + Layout.NPC_TILEX_OFFSET,          tileX);
        seg.set(ValueLayout.JAVA_SHORT, base + Layout.NPC_TILEY_OFFSET,          tileY);
        seg.set(ValueLayout.JAVA_BYTE,  base + Layout.NPC_PLANE_OFFSET,          plane);
        seg.set(ValueLayout.JAVA_BYTE,  base + Layout.NPC_FLAGS_OFFSET,          flags);
        seg.set(ValueLayout.JAVA_SHORT, base + Layout.NPC_FOLLOWINGINDEX_OFFSET, followingIndex);
        seg.set(ValueLayout.JAVA_INT,   base + Layout.NPC_ANIMATIONID_OFFSET,    animationId);
        seg.set(ValueLayout.JAVA_INT,   base + Layout.NPC_STANCEID_OFFSET,       stanceId);
        seg.set(ValueLayout.JAVA_INT,   base + Layout.NPC_HP_OFFSET,             hp);
        seg.set(ValueLayout.JAVA_INT,   base + Layout.NPC_MAXHP_OFFSET,          maxHp);
    }

    private static void writePlayer(MemorySegment seg, int index,
                                    int serverIndex,
                                    short tileX, short tileY, byte plane, byte flags,
                                    short followingIndex, int animationId, int stanceId,
                                    int combatLevel) {
        long base = Layout.SNAP_PLAYERS_OFFSET + (long) index * Layout.PLAYER_ENTRY_SIZE;
        seg.set(ValueLayout.JAVA_INT,   base + Layout.PLAYER_SERVERINDEX_OFFSET,    serverIndex);
        seg.set(ValueLayout.JAVA_SHORT, base + Layout.PLAYER_TILEX_OFFSET,          tileX);
        seg.set(ValueLayout.JAVA_SHORT, base + Layout.PLAYER_TILEY_OFFSET,          tileY);
        seg.set(ValueLayout.JAVA_BYTE,  base + Layout.PLAYER_PLANE_OFFSET,          plane);
        seg.set(ValueLayout.JAVA_BYTE,  base + Layout.PLAYER_FLAGS_OFFSET,          flags);
        seg.set(ValueLayout.JAVA_SHORT, base + Layout.PLAYER_FOLLOWINGINDEX_OFFSET, followingIndex);
        seg.set(ValueLayout.JAVA_INT,   base + Layout.PLAYER_ANIMATIONID_OFFSET,    animationId);
        seg.set(ValueLayout.JAVA_INT,   base + Layout.PLAYER_STANCEID_OFFSET,       stanceId);
        seg.set(ValueLayout.JAVA_INT,   base + Layout.PLAYER_COMBATLEVEL_OFFSET,    combatLevel);
    }

    private static void writeLocation(MemorySegment seg, int index,
                                      int typeId, int interactId, int animationId,
                                      short tileX, short tileY, byte plane,
                                      int shape, int rotation, int flags) {
        long base = Layout.SNAP_LOCATIONS_OFFSET + (long) index * Layout.LOCATION_ENTRY_SIZE;
        seg.set(ValueLayout.JAVA_INT,   base + Layout.LOC_TYPEID_OFFSET,      typeId);
        seg.set(ValueLayout.JAVA_INT,   base + Layout.LOC_INTERACTID_OFFSET,  interactId);
        seg.set(ValueLayout.JAVA_INT,   base + Layout.LOC_ANIMATIONID_OFFSET, animationId);
        seg.set(ValueLayout.JAVA_SHORT, base + Layout.LOC_TILEX_OFFSET,       tileX);
        seg.set(ValueLayout.JAVA_SHORT, base + Layout.LOC_TILEY_OFFSET,       tileY);
        seg.set(ValueLayout.JAVA_BYTE,  base + Layout.LOC_PLANE_OFFSET,       plane);
        seg.set(ValueLayout.JAVA_BYTE,  base + Layout.LOC_SHAPE_OFFSET,       (byte) shape);
        seg.set(ValueLayout.JAVA_BYTE,  base + Layout.LOC_ROTATION_OFFSET,    (byte) rotation);
        seg.set(ValueLayout.JAVA_BYTE,  base + Layout.LOC_FLAGS_OFFSET,       (byte) flags);
    }

    private static void writeInvHeader(MemorySegment seg, int index, int invId,
                                       short slotCount, short firstItemIdx) {
        long base = Layout.SNAP_INVENTORIES_OFFSET + (long) index * Layout.INV_HEADER_SIZE;
        seg.set(ValueLayout.JAVA_INT,   base + Layout.INV_HEADER_INVID_OFFSET,        invId);
        seg.set(ValueLayout.JAVA_SHORT, base + Layout.INV_HEADER_SLOTCOUNT_OFFSET,    slotCount);
        seg.set(ValueLayout.JAVA_SHORT, base + Layout.INV_HEADER_FIRSTITEMIDX_OFFSET, firstItemIdx);
    }

    private static void writeFlatItem(MemorySegment seg, int flatIndex, int itemId, int qty) {
        long base = Layout.SNAP_INVITEMS_OFFSET + (long) flatIndex * Layout.INV_ITEM_SIZE;
        seg.set(ValueLayout.JAVA_INT, base + Layout.INV_ITEM_ITEMID_OFFSET,   itemId);
        seg.set(ValueLayout.JAVA_INT, base + Layout.INV_ITEM_QUANTITY_OFFSET, qty);
    }

    private static void assertArrayEqualsBoxed(int[] expected, int[] actual) {
        assertEquals(expected.length, actual.length, "length");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "index " + i);
        }
    }
}
