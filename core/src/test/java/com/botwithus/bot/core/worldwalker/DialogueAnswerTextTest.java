package com.botwithus.bot.core.worldwalker;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Round-trips answer text through the executor's slot packing. */
class DialogueAnswerTextTest {

    /**
     * Packs {@code text} the way the executor does: its UTF-8 bytes zero-padded
     * to {@link DialogueAnswerText#MAX_BYTES}, copied four bytes per slot in
     * little-endian order.
     */
    static int[] pack(String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(DialogueAnswerText.MAX_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(utf8);
        buffer.rewind();
        int[] slots = new int[DialogueAnswerText.SLOT_COUNT];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = buffer.getInt();
        }
        return slots;
    }

    @Test
    void decode_shortAnswer_roundTrips() {
        assertEquals("Yes.", DialogueAnswerText.decode(pack("Yes.")));
    }

    @Test
    void decode_firstSlotIsLittleEndian() {
        int[] slots = new int[DialogueAnswerText.SLOT_COUNT];
        slots[0] = 'Y' | ('e' << Byte.SIZE) | ('s' << (2 * Byte.SIZE)) | ('.' << (3 * Byte.SIZE));

        assertEquals("Yes.", DialogueAnswerText.decode(slots));
    }

    @Test
    void decode_allZeroSlots_isEmpty() {
        assertEquals("", DialogueAnswerText.decode(new int[DialogueAnswerText.SLOT_COUNT]));
    }

    @Test
    void decode_fullBufferWithNoTerminator_keepsAllBytes() {
        String full = "abcdefghijklmnopqrstuvwxyz0123456789";
        assertEquals(DialogueAnswerText.MAX_BYTES, full.length());

        assertEquals(full, DialogueAnswerText.decode(pack(full)));
    }

    @Test
    void decode_stopsAtFirstZeroByte() {
        int[] slots = pack("Yes.");
        slots[2] = pack("junk")[0];

        assertEquals("Yes.", DialogueAnswerText.decode(slots));
    }

    @Test
    void decode_multiByteUtf8_roundTrips() {
        String accented = "Café au lait";

        assertEquals(accented, DialogueAnswerText.decode(pack(accented)));
    }

    @Test
    void decodeSelection_littleEndianSlots_decodesArdougne() {
        assertEquals("Ardougne", DialogueAnswerText.decodeSelection(0x6F647241, 0x656E6775, 0, 0));
    }

    @Test
    void decodeSelection_allZeroSlots_isEmpty() {
        assertEquals("", DialogueAnswerText.decodeSelection(0, 0, 0, 0));
    }

    @Test
    void decodeSelection_fullBuffer_keepsAllSixteenBytes() {
        assertEquals("abcdefghijklmnop",
                DialogueAnswerText.decodeSelection(0x64636261, 0x68676665, 0x6C6B6A69, 0x706F6E6D));
    }

    @Test
    void decode_wrongSlotCount_throws() {
        assertThrows(IllegalArgumentException.class, () -> DialogueAnswerText.decode(1, 2, 3));
    }
}
