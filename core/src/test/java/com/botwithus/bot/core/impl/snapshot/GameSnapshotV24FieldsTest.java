package com.botwithus.bot.core.impl.snapshot;

import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Npc;
import com.botwithus.bot.api.snapshot.Player;
import com.botwithus.bot.core.shm.Layout;
import com.botwithus.bot.core.shm.SnapshotView;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The v24 snapshot fields, decoded end to end through {@link GameSnapshotImpl}: player names and
 * the truncation flag, NPC active type and combat level, and the local player's run state.
 *
 * <p>Bytes are written at <b>literal</b> offsets from NXTLibrary's SharedLayout.h at c1231ca
 * (the same blob, 833da0e, as the bound head 84a1376), checked there by compiling the header
 * and printing {@code offsetof}. They are not written through
 * {@link Layout}: a fixture written through the reader's own constants would round-trip whatever
 * they said. Row 1 is written as well as row 0 so each stride is pinned with its field.</p>
 */
class GameSnapshotV24FieldsTest {

    private static final long GAME_STATE = 8;
    private static final long OWN_INDEX = 12;
    private static final long SELF = 24;
    private static final long SELF_RUN_ENERGY = 552;
    private static final long SELF_RUN_WEIGHT = 556;
    private static final long NPC_COUNT = 584;
    private static final long NPCS = 588;
    private static final long NPC_STRIDE = 48;
    private static final long NPC_TYPE_ID = 4;
    private static final long NPC_ACTIVE_TYPE_ID = 40;
    private static final long NPC_COMBAT_LEVEL = 44;
    private static final long PLAYER_COUNT = 49740;
    private static final long PLAYERS = 49744;
    private static final long PLAYER_STRIDE = 48;
    private static final long PLAYER_FLAGS = 9;
    private static final long PLAYER_NAME = 32;
    private static final int NAME_CAP = 16;
    private static final int FLAG_NAME_TRUNCATED = 0x2;

    private static final int IN_GAME = 30;
    private static final int LOBBY = 20;
    private static final int OWN_SERVER_INDEX = 7;
    private static final int XENIA_BASE = 9633;
    private static final int XENIA_ACTIVE = 11476;
    private static final int THE_SAGE_BASE = 32248;
    private static final int THE_SAGE_ACTIVE = 32250;
    private static final int COMBAT = 12;
    private static final int RUN_ENERGY = 87;
    private static final int RUN_WEIGHT = 14;

    @Test
    void npcRows_carryBaseAndActiveTypeAndCombatLevel() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(Layout.SNAPSHOT_SIZE, Long.BYTES);
            seg.set(ValueLayout.JAVA_INT, NPC_COUNT, 2);
            writeNpc(seg, 0, XENIA_BASE, XENIA_ACTIVE, COMBAT);
            writeNpc(seg, 1, THE_SAGE_BASE, -1, 0);

            GameSnapshot snap = new GameSnapshotImpl(new SnapshotView(seg));
            Npc xenia = snap.npcs().at(0);
            Npc hidden = snap.npcs().at(1);

            assertEquals(XENIA_BASE, xenia.typeId());
            assertEquals(XENIA_ACTIVE, xenia.activeTypeId());
            assertEquals(COMBAT, xenia.combatLevel());
            assertTrue(xenia.isMorphed());
            assertFalse(xenia.isHidden());
            assertEquals(XENIA_ACTIVE, xenia.appearanceTypeId());
            assertEquals(THE_SAGE_BASE, hidden.typeId());
            assertTrue(hidden.isHidden(), "activeTypeId -1 over a resolved base is hidden");
            assertEquals(THE_SAGE_BASE, hidden.appearanceTypeId(), "a hidden NPC falls back to its base");
        }
    }

    @Test
    void secondMorphFixture_theSage() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(Layout.SNAPSHOT_SIZE, Long.BYTES);
            seg.set(ValueLayout.JAVA_INT, NPC_COUNT, 1);
            writeNpc(seg, 0, THE_SAGE_BASE, THE_SAGE_ACTIVE, 0);

            Npc sage = new GameSnapshotImpl(new SnapshotView(seg)).npcs().at(0);

            assertEquals(THE_SAGE_ACTIVE, sage.appearanceTypeId());
            assertEquals(0, sage.combatLevel());
        }
    }

    @Test
    void playerRows_decodeNamesUpToTheFirstNul() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(Layout.SNAPSHOT_SIZE, Long.BYTES);
            seg.set(ValueLayout.JAVA_INT, PLAYER_COUNT, 2);
            writePlayerName(seg, 0, "Zezima".getBytes(StandardCharsets.UTF_8), 0);
            writePlayerName(seg, 1, new byte[0], 0);

            GameSnapshot snap = new GameSnapshotImpl(new SnapshotView(seg));

            assertEquals("Zezima", snap.players().at(0).name());
            assertTrue(snap.players().at(0).hasName());
            assertEquals("", snap.players().at(1).name(), "an all-zero name is not known yet");
            assertFalse(snap.players().at(1).hasName());
        }
    }

    @Test
    void playerRows_decodeMultibyteUtf8AndTheNoBreakSpaceRaw() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(Layout.SNAPSHOT_SIZE, Long.BYTES);
            seg.set(ValueLayout.JAVA_INT, PLAYER_COUNT, 2);
            String accented = "\u00C5lfa \u00D8st";
            String nbsp = "Sir\u00A0Test";
            writePlayerName(seg, 0, accented.getBytes(StandardCharsets.UTF_8), 0);
            writePlayerName(seg, 1, nbsp.getBytes(StandardCharsets.UTF_8), 0);

            GameSnapshot snap = new GameSnapshotImpl(new SnapshotView(seg));

            assertEquals(accented, snap.players().at(0).name());
            assertEquals(nbsp, snap.players().at(1).name(), "the snapshot keeps U+00A0 as sent");
        }
    }

    @Test
    void truncatedName_carriesTheFlagAndTheFifteenBytePrefix() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(Layout.SNAPSHOT_SIZE, Long.BYTES);
            seg.set(ValueLayout.JAVA_INT, PLAYER_COUNT, 1);
            writePlayerName(seg, 0, "ABCDEFGHIJKLMNO".getBytes(StandardCharsets.UTF_8), FLAG_NAME_TRUNCATED);

            Player p = new GameSnapshotImpl(new SnapshotView(seg)).players().at(0);

            assertTrue(p.isNameTruncated());
            assertFalse(p.isMoving(), "the truncation bit must not read as the moving bit");
            assertEquals("ABCDEFGHIJKLMNO", p.name());
        }
    }

    /** A producer bug that leaves no NUL must still decode, bounded to the field. */
    @Test
    void nameWithNoNul_isBoundedToTheField() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(Layout.SNAPSHOT_SIZE, Long.BYTES);
            seg.set(ValueLayout.JAVA_INT, PLAYER_COUNT, 2);
            writePlayerName(seg, 0, "ABCDEFGHIJKLMNOP".getBytes(StandardCharsets.UTF_8), 0);
            writePlayerName(seg, 1, "Next".getBytes(StandardCharsets.UTF_8), 0);

            GameSnapshot snap = new GameSnapshotImpl(new SnapshotView(seg));

            assertEquals("ABCDEFGHIJKLMNOP", snap.players().at(0).name());
            assertEquals("Next", snap.players().at(1).name());
        }
    }

    @Test
    void self_inWorld_reportsRunEnergyAndWeight() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = selfSnapshot(arena, IN_GAME, RUN_ENERGY, RUN_WEIGHT);

            LocalPlayer self = new GameSnapshotImpl(new SnapshotView(seg)).self();

            assertEquals(RUN_ENERGY, self.runEnergy());
            assertEquals(RUN_WEIGHT, self.runWeight());
            assertTrue(self.hasRunEnergy());
        }
    }

    @Test
    void self_inWorldButUnreadable_passesTheWireUnknownThrough() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = selfSnapshot(arena, IN_GAME, LocalPlayer.RUN_UNKNOWN, LocalPlayer.RUN_UNKNOWN);

            LocalPlayer self = new GameSnapshotImpl(new SnapshotView(seg)).self();

            assertFalse(self.hasRunEnergy());
            assertEquals(LocalPlayer.RUN_UNKNOWN, self.runWeight());
        }
    }

    /** The wire's out-of-world 0 is reported as unknown, so 0 always means an empty bar. */
    @Test
    void self_outOfWorld_reportsUnknownRatherThanTheWireZero() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = selfSnapshot(arena, LOBBY, 0, 0);

            LocalPlayer self = new GameSnapshotImpl(new SnapshotView(seg)).self();

            assertEquals(LocalPlayer.RUN_UNKNOWN, self.runEnergy());
            assertEquals(LocalPlayer.RUN_UNKNOWN, self.runWeight());
        }
    }

    @Test
    void self_withNoOwnIndex_isNull() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(Layout.SNAPSHOT_SIZE, Long.BYTES);
            seg.set(ValueLayout.JAVA_INT, OWN_INDEX, -1);
            assertNull(new GameSnapshotImpl(new SnapshotView(seg)).self());
        }
    }

    private static MemorySegment selfSnapshot(Arena arena, int gameState, int energy, int weight) {
        MemorySegment seg = arena.allocate(Layout.SNAPSHOT_SIZE, Long.BYTES);
        seg.set(ValueLayout.JAVA_INT, GAME_STATE, gameState);
        seg.set(ValueLayout.JAVA_INT, OWN_INDEX, OWN_SERVER_INDEX);
        seg.set(ValueLayout.JAVA_INT, SELF, OWN_SERVER_INDEX);
        seg.set(ValueLayout.JAVA_INT, SELF + SELF_RUN_ENERGY, energy);
        seg.set(ValueLayout.JAVA_INT, SELF + SELF_RUN_WEIGHT, weight);
        return seg;
    }

    private static void writeNpc(MemorySegment seg, int row, int baseId, int activeId, int combat) {
        long base = NPCS + row * NPC_STRIDE;
        seg.set(ValueLayout.JAVA_INT, base + NPC_TYPE_ID, baseId);
        seg.set(ValueLayout.JAVA_INT, base + NPC_ACTIVE_TYPE_ID, activeId);
        seg.set(ValueLayout.JAVA_INT, base + NPC_COMBAT_LEVEL, combat);
    }

    private static void writePlayerName(MemorySegment seg, int row, byte[] name, int flags) {
        long base = PLAYERS + row * PLAYER_STRIDE;
        seg.set(ValueLayout.JAVA_BYTE, base + PLAYER_FLAGS, (byte) flags);
        for (int i = 0; i < NAME_CAP; i++) {
            byte b = i < name.length ? name[i] : 0;
            seg.set(ValueLayout.JAVA_BYTE, base + PLAYER_NAME + i, b);
        }
    }
}
