package com.botwithus.bot.core.shm;

import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.api.event.SpotAnimEvent;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link EventRingReader}'s producer-supplied geometry validation
 * (H5) and the per-poll drain cap (M8), using the package-private constructor
 * seam that accepts a fabricated ring segment.
 */
class EventRingGeometryTest {

    private static final long RING_BYTES =
            Layout.RING_SLOTS_OFFSET + (long) Layout.EVENT_RING_SLOTS * Layout.EVENT_SLOT_SIZE;

    private static MemorySegment ring(Arena arena, int slotCount, int slotMask, long head) {
        MemorySegment ring = arena.allocate(RING_BYTES);
        ring.set(ValueLayout.JAVA_INT, Layout.RING_SLOTCOUNT_OFFSET, slotCount);
        ring.set(ValueLayout.JAVA_INT, Layout.RING_SLOTMASK_OFFSET, slotMask);
        ring.set(ValueLayout.JAVA_LONG, Layout.RING_HEAD_OFFSET, head);
        return ring;
    }

    @Test
    void validRingConstructs() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ring = ring(arena, Layout.EVENT_RING_SLOTS, Layout.EVENT_RING_SLOTS - 1, 0);
            assertDoesNotThrow(() -> new EventRingReader(ring, true));
        }
    }

    @Test
    void wrongSlotMaskRejected() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ring = ring(arena, Layout.EVENT_RING_SLOTS, 0xFFFF, 0);
            assertThrows(SharedMemoryException.class, () -> new EventRingReader(ring, true));
        }
    }

    @Test
    void negativeSlotMaskRejected() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ring = ring(arena, Layout.EVENT_RING_SLOTS, -1, 0);
            assertThrows(SharedMemoryException.class, () -> new EventRingReader(ring, true));
        }
    }

    @Test
    void wrongSlotCountRejected() {
        try (Arena arena = Arena.ofConfined()) {
            int half = Layout.EVENT_RING_SLOTS / 2;
            MemorySegment ring = ring(arena, half, half - 1, 0);
            assertThrows(SharedMemoryException.class, () -> new EventRingReader(ring, true));
        }
    }

    @Test
    void spotAnimEventDecodes() {
        try (Arena arena = Arena.ofConfined()) {
            // Construct from head=0 so nextSeq=0, then commit one event in slot 0
            // and publish it by advancing head to 1.
            MemorySegment ring = ring(arena, Layout.EVENT_RING_SLOTS, Layout.EVENT_RING_SLOTS - 1, 0);
            EventRingReader reader = new EventRingReader(ring, true);

            long slot = Layout.RING_SLOTS_OFFSET;   // seq 0 maps to slot 0
            ring.set(ValueLayout.JAVA_INT, slot + Layout.SLOT_TYPE_OFFSET, Layout.EVT_SPOT_ANIM);
            ring.set(ValueLayout.JAVA_INT, slot + Layout.SLOT_BODYLEN_OFFSET, 20);
            long body = slot + Layout.SLOT_BODY_OFFSET;
            ring.set(ValueLayout.JAVA_INT,   body,      77);            // targetServerIndex
            ring.set(ValueLayout.JAVA_BYTE,  body + 4,  (byte) 1);      // targetType = npc
            ring.set(ValueLayout.JAVA_INT,   body + 8,  4567);          // spotAnimId
            ring.set(ValueLayout.JAVA_SHORT, body + 12, (short) 3200);  // tileX
            ring.set(ValueLayout.JAVA_SHORT, body + 14, (short) 3300);  // tileY
            ring.set(ValueLayout.JAVA_BYTE,  body + 16, (byte) 2);      // plane
            ring.set(ValueLayout.JAVA_LONG,  slot + Layout.SLOT_SEQ_OFFSET, 0L);  // commit
            ring.set(ValueLayout.JAVA_LONG,  Layout.RING_HEAD_OFFSET, 1L);        // publish

            List<GameEvent> out = new ArrayList<>();
            reader.poll(out::add);

            assertEquals(1, out.size());
            SpotAnimEvent e = assertInstanceOf(SpotAnimEvent.class, out.get(0));
            assertEquals(77, e.targetServerIndex());
            assertEquals(1, e.targetType());
            assertEquals(4567, e.spotAnimId());
            assertEquals(3200, e.tileX());
            assertEquals(3300, e.tileY());
            assertEquals(2, e.plane());
        }
    }

    @Test
    void pollClampsRunawayHeadAndCountsDrops() {
        try (Arena arena = Arena.ofConfined()) {
            // Start from head=0 (nextSeq=0), then a hostile/buggy producer jumps
            // head far ahead. poll() must clamp the drain to one ring-span and
            // account the rest as drops rather than spin over millions of slots.
            MemorySegment ring = ring(arena, Layout.EVENT_RING_SLOTS, Layout.EVENT_RING_SLOTS - 1, 0);
            EventRingReader reader = new EventRingReader(ring, true);

            long runawayHead = 5_000_000L;
            ring.set(ValueLayout.JAVA_LONG, Layout.RING_HEAD_OFFSET, runawayHead);

            List<GameEvent> delivered = new ArrayList<>();
            reader.poll(delivered::add);

            assertEquals(runawayHead - Layout.EVENT_RING_SLOTS, reader.droppedCount());
            assertTrue(delivered.isEmpty());
        }
    }
}
