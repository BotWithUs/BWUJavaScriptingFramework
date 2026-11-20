package com.botwithus.bot.core.worldwalker;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Decodes packed text from WorldWalker dialogue steps. Answers use slots
 * {@code a..i} as UTF-8, and chooser labels use slots {@code f..i} as ASCII.
 *
 * <p>Each slot holds four bytes in little-endian order. The text ends at the
 * first zero byte or fills the whole buffer.</p>
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
        return decodeSlots(StandardCharsets.UTF_8, slots);
    }

    /** Decodes the optional ASCII chooser text from slots {@code f..i}. */
    public static String decodeSelection(int f, int g, int h, int i) {
        return decodeSlots(StandardCharsets.US_ASCII, f, g, h, i);
    }

    private static String decodeSlots(Charset charset, int... slots) {
        ByteBuffer buffer = ByteBuffer.allocate(slots.length * Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int slot : slots) {
            buffer.putInt(slot);
        }
        byte[] bytes = buffer.array();
        return new String(bytes, 0, textLength(bytes), charset);
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
