package com.botwithus.bot.core.shm;

import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.api.event.KeyInputEvent;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes hand-built key-input bodies through the real {@link EventRingReader} poll path.
 *
 * <p>The body offsets and flag bits below are written as literals copied from the
 * producer's {@code KeyInputBody} and {@code kKeyFlag*} definitions, deliberately not
 * taken from {@link Layout}: a test that reused the decoder's own constants could not
 * notice those constants being wrong.</p>
 */
class KeyInputDecodeTest {

    private static final long RING_BYTES =
            Layout.RING_SLOTS_OFFSET + (long) Layout.EVENT_RING_SLOTS * Layout.EVENT_SLOT_SIZE;

    // KeyInputBody, as the producer lays it out.
    private static final int BODY_BYTES = 8;
    private static final int KEY_AT = 0;
    private static final int ALT_AT = 4;
    private static final int CTRL_AT = 5;
    private static final int SHIFT_AT = 6;
    private static final int FLAGS_AT = 7;

    // kKeyFlag* bits, as the producer defines them.
    private static final int SYNTHETIC = 0x01;
    private static final int TEXT_INPUT_FOCUSED = 0x02;
    private static final int REPEAT = 0x04;
    private static final int RESERVED_BITS = 0xF8;

    private static final int VK_T = 0x54;
    private static final int VK_SHIFT = 0x10;
    private static final byte HELD = 1;
    private static final byte NOT_HELD = 0;

    /** One key-input body; {@code bodyLen} is what the slot header claims. */
    private record Body(int key, byte alt, byte ctrl, byte shift, int flags, int bodyLen) {

        static Body withFlags(int flags) {
            return new Body(VK_T, NOT_HELD, NOT_HELD, NOT_HELD, flags, BODY_BYTES);
        }
    }

    /** Commits {@code body} as seq 0 in a fresh ring and returns what one poll delivers. */
    private static List<GameEvent> decode(Body body) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ring = arena.allocate(RING_BYTES);
            ring.set(ValueLayout.JAVA_INT, Layout.RING_SLOTCOUNT_OFFSET, Layout.EVENT_RING_SLOTS);
            ring.set(ValueLayout.JAVA_INT, Layout.RING_SLOTMASK_OFFSET,
                    Layout.EVENT_RING_SLOTS - 1);
            EventRingReader reader = new EventRingReader(ring, true);

            long slot = Layout.RING_SLOTS_OFFSET;
            ring.set(ValueLayout.JAVA_INT, slot + Layout.SLOT_TYPE_OFFSET, Layout.EVT_KEY_INPUT);
            ring.set(ValueLayout.JAVA_INT, slot + Layout.SLOT_BODYLEN_OFFSET, body.bodyLen());
            long at = slot + Layout.SLOT_BODY_OFFSET;
            ring.set(ValueLayout.JAVA_INT, at + KEY_AT, body.key());
            ring.set(ValueLayout.JAVA_BYTE, at + ALT_AT, body.alt());
            ring.set(ValueLayout.JAVA_BYTE, at + CTRL_AT, body.ctrl());
            ring.set(ValueLayout.JAVA_BYTE, at + SHIFT_AT, body.shift());
            ring.set(ValueLayout.JAVA_BYTE, at + FLAGS_AT, (byte) body.flags());
            ring.set(ValueLayout.JAVA_LONG, slot + Layout.SLOT_SEQ_OFFSET, 0L);
            ring.set(ValueLayout.JAVA_LONG, Layout.RING_HEAD_OFFSET, 1L);

            List<GameEvent> out = new ArrayList<>();
            reader.poll(out::add);
            assertEquals(1L, reader.nextSeq(), "the slot is consumed whether or not it decodes");
            return out;
        }
    }

    private static KeyInputEvent decodeOne(Body body) {
        List<GameEvent> out = decode(body);
        assertEquals(1, out.size());
        return assertInstanceOf(KeyInputEvent.class, out.get(0));
    }

    private static void assertFlags(KeyInputEvent e, boolean synthetic,
                                    boolean textInputFocused, boolean repeat) {
        assertEquals(synthetic, e.synthetic(), "synthetic (bit 0)");
        assertEquals(textInputFocused, e.textInputFocused(), "textInputFocused (bit 1)");
        assertEquals(repeat, e.repeat(), "repeat (bit 2)");
    }

    @Test
    void flagsByteZero_allFlagsFalse_asFromAnOlderAgent() {
        assertFlags(decodeOne(Body.withFlags(0)), false, false, false);
    }

    @Test
    void bit0Alone_isSyntheticOnly() {
        assertFlags(decodeOne(Body.withFlags(SYNTHETIC)), true, false, false);
    }

    @Test
    void bit1Alone_isTextInputFocusedOnly() {
        assertFlags(decodeOne(Body.withFlags(TEXT_INPUT_FOCUSED)), false, true, false);
    }

    @Test
    void bit2Alone_isRepeatOnly() {
        assertFlags(decodeOne(Body.withFlags(REPEAT)), false, false, true);
    }

    @Test
    void allThreeBits_setAllThreeFlags() {
        assertFlags(decodeOne(Body.withFlags(SYNTHETIC | TEXT_INPUT_FOCUSED | REPEAT)),
                true, true, true);
    }

    @Test
    void reservedBitsAlone_areIgnoredAndTheEventStillArrives() {
        KeyInputEvent e = decodeOne(Body.withFlags(RESERVED_BITS));
        assertEquals(VK_T, e.key());
        assertFlags(e, false, false, false);
    }

    @Test
    void reservedBitsAlongsideKnownOnes_doNotDisturbThem() {
        assertFlags(decodeOne(Body.withFlags(RESERVED_BITS | SYNTHETIC | REPEAT)),
                true, false, true);
    }

    @Test
    void bodyShorterThanEightBytes_isDropped() {
        Body shortBody = new Body(VK_T, HELD, HELD, HELD, SYNTHETIC, BODY_BYTES - 1);
        assertTrue(decode(shortBody).isEmpty());
    }

    @Test
    void bodyLongerThanEightBytes_decodesTheFirstEight() {
        Body longBody = new Body(VK_T, NOT_HELD, NOT_HELD, NOT_HELD, REPEAT,
                Layout.EVENT_BODY_MAX);
        assertFlags(decodeOne(longBody), false, false, true);
    }

    @Test
    void eachModifierByte_mapsToItsOwnField() {
        KeyInputEvent alt = decodeOne(new Body(VK_T, HELD, NOT_HELD, NOT_HELD, 0, BODY_BYTES));
        assertTrue(alt.alt());
        assertFalse(alt.ctrl());
        assertFalse(alt.shift());

        KeyInputEvent ctrl = decodeOne(new Body(VK_T, NOT_HELD, HELD, NOT_HELD, 0, BODY_BYTES));
        assertFalse(ctrl.alt());
        assertTrue(ctrl.ctrl());
        assertFalse(ctrl.shift());

        KeyInputEvent shift = decodeOne(new Body(VK_T, NOT_HELD, NOT_HELD, HELD, 0, BODY_BYTES));
        assertFalse(shift.alt());
        assertFalse(shift.ctrl());
        assertTrue(shift.shift());
    }

    @Test
    void modifiersAndFlags_doNotBleedIntoEachOther() {
        KeyInputEvent noFlags = decodeOne(new Body(VK_T, HELD, HELD, HELD, 0, BODY_BYTES));
        assertFlags(noFlags, false, false, false);

        KeyInputEvent noMods = decodeOne(Body.withFlags(SYNTHETIC | TEXT_INPUT_FOCUSED | REPEAT));
        assertFalse(noMods.alt());
        assertFalse(noMods.ctrl());
        assertFalse(noMods.shift());
    }

    @Test
    void shiftPressedAlone_reportsTheGenericCodeWithShiftHeld() {
        KeyInputEvent e = decodeOne(new Body(VK_SHIFT, NOT_HELD, NOT_HELD, HELD, 0, BODY_BYTES));
        assertEquals(VK_SHIFT, e.key());
        assertTrue(e.shift());
    }
}
