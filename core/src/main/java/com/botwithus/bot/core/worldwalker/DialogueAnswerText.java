package com.botwithus.bot.core.worldwalker;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Decodes the answer text a {@link ChainStepKind#DIALOGUE_ANSWER} step carries
 * in its nine generic {@code int} slots.
 *
 * <p>The executor copies a fixed-size UTF-8 buffer straight into the slots, so
 * each slot holds four bytes of the text in little-endian order, slot {@code a}
 * first. The text ends at the first zero byte, or fills the whole buffer with
 * no terminator at all.</p>
 */
public final class DialogueAnswerText {

    /** Number of generic {@code int} slots a chain step carries. */
    public static final int SLOT_COUNT = 9;

    /** Largest answer, in UTF-8 bytes, the slots can carry. */
    public static final int MAX_BYTES = SLOT_COUNT * Integer.BYTES;

    /** The byte that ends an answer shorter than {@link #MAX_BYTES}. */
    private static final byte TERMINATOR = 0;

    private DialogueAnswerText() {}

    /**
     * The answer the slots carry, or an empty string when the first byte is the
     * terminator.
     *
     * @param slots the step's slots {@code a..i}, in order
     * @throws IllegalArgumentException when {@code slots} is not exactly
     *         {@link #SLOT_COUNT} long
     */
    public static String decode(int... slots) {
        if (slots.length != SLOT_COUNT) {
            throw new IllegalArgumentException(
                    "a dialogue answer spans " + SLOT_COUNT + " slots, got " + slots.length);
        }
        ByteBuffer buffer = ByteBuffer.allocate(MAX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int slot : slots) {
            buffer.putInt(slot);
        }
        byte[] bytes = buffer.array();
        return new String(bytes, 0, textLength(bytes), StandardCharsets.UTF_8);
    }

    /** Index of the first terminator, or the whole length when there is none. */
    private static int textLength(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == TERMINATOR) {
                return i;
            }
        }
        return bytes.length;
    }
}
