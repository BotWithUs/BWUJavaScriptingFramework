package com.botwithus.bot.core.shm;

import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.api.event.HeadbarEvent;
import com.botwithus.bot.api.event.HitmarkEvent;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes wire events 70 (hitmark) and 71 (head bar) from a fabricated ring.
 *
 * <p>The body offsets below are written as literals copied from {@code HitmarkBody} /
 * {@code HeadbarBody} in the agent's {@code Events.h}, not taken from {@link Layout}, so a
 * drift in {@code Layout}'s constants fails here instead of agreeing with itself. Every field
 * holds a distinct non-default value, so a decoder that read one field from another's offset
 * would produce a visibly wrong record.</p>
 */
class HitmarkHeadbarDecodeTest {

    private static final long RING_BYTES =
            Layout.RING_SLOTS_OFFSET + (long) Layout.EVENT_RING_SLOTS * Layout.EVENT_SLOT_SIZE;

    @Test
    void hitmark_decodesEveryFieldFromItsOffset() {
        List<GameEvent> events = drainOne(Layout.EVT_HITMARK, 20, (ring, body) -> {
            ring.set(ValueLayout.JAVA_INT, body, 1234);             // targetServerIndex
            ring.set(ValueLayout.JAVA_BYTE, body + 4, (byte) 1);    // targetType = npc
            ring.set(ValueLayout.JAVA_INT, body + 8, 144);          // hitmarkType
            ring.set(ValueLayout.JAVA_INT, body + 12, 987);         // damage
            ring.set(ValueLayout.JAVA_INT, body + 16, 5_550_001);   // cycle
        });

        HitmarkEvent hit = assertInstanceOf(HitmarkEvent.class, events.getFirst());
        assertEquals(1234, hit.targetServerIndex());
        assertEquals(HitmarkEvent.TARGET_NPC, hit.targetType());
        assertTrue(hit.isOnNpc());
        assertEquals(144, hit.hitmarkType());
        assertEquals(987, hit.damage());
        assertEquals(5_550_001, hit.cycle());
    }

    @Test
    void headbar_decodesEveryFieldFromItsOffset() {
        List<GameEvent> events = drainOne(Layout.EVT_HEADBAR, 16, (ring, body) -> {
            ring.set(ValueLayout.JAVA_INT, body, 42);
            ring.set(ValueLayout.JAVA_BYTE, body + 4, (byte) 0);    // player
            ring.set(ValueLayout.JAVA_INT, body + 8, 7);            // adrenaline bar
            ring.set(ValueLayout.JAVA_INT, body + 12, 201);
        });

        HeadbarEvent bar = assertInstanceOf(HeadbarEvent.class, events.getFirst());
        assertEquals(42, bar.targetServerIndex());
        assertEquals(HitmarkEvent.TARGET_PLAYER, bar.targetType());
        assertEquals(7, bar.headbarType());
        assertEquals(201, bar.value());
    }

    @Test
    void shortBodies_areSkippedNotMisread() {
        assertTrue(drainOne(Layout.EVT_HITMARK, 19, (ring, body) -> { }).isEmpty());
        assertTrue(drainOne(Layout.EVT_HEADBAR, 15, (ring, body) -> { }).isEmpty());
    }

    @Test
    void layoutConstants_matchTheProducerBodySizes() {
        assertEquals(20, Layout.HITMARK_BODY_BYTES);
        assertEquals(16, Layout.HEADBAR_BODY_BYTES);
        assertEquals(70, Layout.EVT_HITMARK);
        assertEquals(71, Layout.EVT_HEADBAR);
    }

    private interface BodyWriter {
        void write(MemorySegment ring, long body);
    }

    private static List<GameEvent> drainOne(int type, int bodyLen, BodyWriter writer) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ring = arena.allocate(RING_BYTES);
            ring.set(ValueLayout.JAVA_INT, Layout.RING_SLOTCOUNT_OFFSET, Layout.EVENT_RING_SLOTS);
            ring.set(ValueLayout.JAVA_INT, Layout.RING_SLOTMASK_OFFSET, Layout.EVENT_RING_SLOTS - 1);
            EventRingReader reader = new EventRingReader(ring, true);
            long slot = Layout.RING_SLOTS_OFFSET;
            ring.set(ValueLayout.JAVA_INT, slot + Layout.SLOT_TYPE_OFFSET, type);
            ring.set(ValueLayout.JAVA_INT, slot + Layout.SLOT_BODYLEN_OFFSET, bodyLen);
            writer.write(ring, slot + Layout.SLOT_BODY_OFFSET);
            ring.set(ValueLayout.JAVA_LONG, slot + Layout.SLOT_SEQ_OFFSET, 0L);
            ring.set(ValueLayout.JAVA_LONG, Layout.RING_HEAD_OFFSET, 1L);
            List<GameEvent> events = new ArrayList<>();
            reader.poll(events::add);
            return events;
        }
    }
}
