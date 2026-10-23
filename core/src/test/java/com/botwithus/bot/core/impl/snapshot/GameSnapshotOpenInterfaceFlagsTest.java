package com.botwithus.bot.core.impl.snapshot;

import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.OpenInterface;
import com.botwithus.bot.core.shm.Layout;
import com.botwithus.bot.core.shm.SnapshotView;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes a v23 open-interface fixture written the way the producer lays it out.
 *
 * <p>The fixture writes at the producer header's literal offsets rather than at
 * {@link Layout}'s derived ones. A fixture that wrote through {@link Layout} would round-trip
 * with any offset at all, so it could not notice the reader looking in the wrong place.</p>
 */
class GameSnapshotOpenInterfaceFlagsTest {

    // offsetof(Snapshot, ...) in the v23 SharedLayout.h.
    private static final long WIRE_OPEN_IFACE_COUNT = 320376;
    private static final long WIRE_OPEN_IFACE_TOTAL = 320380;
    private static final long WIRE_OPEN_IFACES = 320384;
    private static final long WIRE_OPEN_IFACE_FLAGS = 321408;
    private static final long WIRE_GROUND_ITEM_COUNT = 321668;
    private static final long WIRE_GROUND_ITEMS = 321672;
    private static final long WIRE_GAME_CYCLE = 346252;
    private static final long WIRE_DYN_REGION = 346256;

    private static final int BANK = 517;
    private static final int HUD = 1465;
    private static final int CLIENT_CHILD = 1432;
    private static final int ABSENT = 1477;

    private static final int FLAGS_MODAL = 0x00;
    private static final int FLAGS_OVERLAY = 0x01;
    private static final int FLAGS_CHILD_CLIENT_OPENED = 0x0B;
    private static final int STALE_FLAGS = 0xFF;

    private static final int GROUND_ITEM_ID = 995;
    private static final int GAME_CYCLE = 123456;
    private static final int DYNAMIC_SCENE_MODE = 5;

    @Test
    void flagsDecodePerIdAtTheSameIndex() {
        try (Arena arena = Arena.ofConfined()) {
            GameSnapshot snap = build(fixture(arena));

            assertEquals(List.of(
                    new OpenInterface(BANK, OpenInterface.TYPE_MODAL, false),
                    new OpenInterface(HUD, OpenInterface.TYPE_OVERLAY, false),
                    new OpenInterface(CLIENT_CHILD, OpenInterface.TYPE_CLIENT_CHILD, true)),
                    snap.openInterfaces());
        }
    }

    @Test
    void bankIsModalAndHudIsOverlay() {
        try (Arena arena = Arena.ofConfined()) {
            GameSnapshot snap = build(fixture(arena));

            assertEquals(Optional.of(true), snap.openInterface(BANK).map(OpenInterface::isModal));
            assertEquals(Optional.of(true), snap.openInterface(HUD).map(OpenInterface::isOverlay));
            assertEquals(Optional.of(false), snap.openInterface(HUD).map(OpenInterface::isModal));
            assertTrue(snap.openInterface(CLIENT_CHILD).orElseThrow().clientOpened());
            assertEquals(Optional.empty(), snap.openInterface(ABSENT));
        }
    }

    /** Bytes past the count are stale by contract and must never surface. */
    @Test
    void flagsPastTheCountAreNeverRead() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = fixture(arena);
            SnapshotView view = new SnapshotView(seg);

            assertEquals(3, view.openInterfaces().size());
            assertThrows(IndexOutOfBoundsException.class, () -> view.openIfaceFlagsAt(3));
        }
    }

    /** The default and the live override must agree, so a stub cannot answer differently. */
    @Test
    void liveLookupAgreesWithScanningTheList() {
        try (Arena arena = Arena.ofConfined()) {
            GameSnapshot snap = build(fixture(arena));

            for (OpenInterface open : snap.openInterfaces()) {
                assertEquals(Optional.of(open), snap.openInterface(open.id()));
            }
        }
    }

    /** Every field from the old pad on moved by 256; the moved tail must decode where it is. */
    @Test
    void tailFieldsDecodeAtTheirMovedOffsets() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = fixture(arena);
            seg.set(ValueLayout.JAVA_INT, WIRE_GROUND_ITEM_COUNT, 1);
            seg.set(ValueLayout.JAVA_INT, WIRE_GROUND_ITEMS, GROUND_ITEM_ID);
            seg.set(ValueLayout.JAVA_INT, WIRE_GAME_CYCLE, GAME_CYCLE);
            seg.set(ValueLayout.JAVA_BYTE, WIRE_DYN_REGION, (byte) 1);
            seg.set(ValueLayout.JAVA_INT, WIRE_DYN_REGION + Layout.DYNREGION_SCENEMODE_OFFSET,
                    DYNAMIC_SCENE_MODE);

            GameSnapshot snap = build(seg);

            assertEquals(1, snap.groundItems().count());
            assertEquals(GROUND_ITEM_ID, snap.groundItems().at(0).itemId());
            assertEquals(GAME_CYCLE, snap.gameCycle());
            assertTrue(snap.dynamicRegion().isInstance());
            assertEquals(DYNAMIC_SCENE_MODE, snap.dynamicRegion().sceneMode());
            assertEquals(3, snap.openInterfaces().size(), "the tail writes must not touch the flags");
        }
    }

    private static MemorySegment fixture(Arena arena) {
        MemorySegment seg = arena.allocate(Layout.SNAPSHOT_SIZE, Long.BYTES);
        int[] ids = {BANK, HUD, CLIENT_CHILD};
        int[] flags = {FLAGS_MODAL, FLAGS_OVERLAY, FLAGS_CHILD_CLIENT_OPENED};
        seg.set(ValueLayout.JAVA_INT, WIRE_OPEN_IFACE_COUNT, ids.length);
        seg.set(ValueLayout.JAVA_INT, WIRE_OPEN_IFACE_TOTAL, ids.length);
        for (int i = 0; i < ids.length; i++) {
            seg.set(ValueLayout.JAVA_INT, WIRE_OPEN_IFACES + (long) i * Integer.BYTES, ids[i]);
            seg.set(ValueLayout.JAVA_BYTE, WIRE_OPEN_IFACE_FLAGS + i, (byte) flags[i]);
        }
        seg.set(ValueLayout.JAVA_BYTE, WIRE_OPEN_IFACE_FLAGS + ids.length, (byte) STALE_FLAGS);
        return seg;
    }

    private static GameSnapshot build(MemorySegment seg) {
        return new GameSnapshotImpl(new SnapshotView(seg));
    }
}
