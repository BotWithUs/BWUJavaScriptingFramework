package com.botwithus.bot.core.shm;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * Decodes {@code PlayerEntry::name} (v24): a {@link Layout#PLAYER_NAME_CAP}-byte UTF-8 field the
 * producer always NUL-terminates. Reads up to the first NUL, or the whole field if a producer bug
 * left none. Malformed UTF-8 decodes to U+FFFD rather than throwing: a snapshot read never throws
 * for a bad byte.
 */
final class PlayerNameDecoder {

    private PlayerNameDecoder() {
    }

    /** The name at {@code offset} in {@code seg}; {@code ""} when the first byte is NUL. */
    static String decode(MemorySegment seg, long offset) {
        int length = 0;
        while (length < Layout.PLAYER_NAME_CAP
                && seg.get(ValueLayout.JAVA_BYTE, offset + length) != 0) {
            length++;
        }
        if (length == 0) {
            return "";
        }
        byte[] bytes = new byte[length];
        MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, offset, bytes, 0, length);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
