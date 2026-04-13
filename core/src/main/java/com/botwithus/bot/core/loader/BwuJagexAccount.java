package com.botwithus.bot.core.loader;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * A Jagex OAuth account with session state and linked characters.
 *
 * @param uuid              internal account UUID
 * @param subject           Jagex unique user ID (JWT {@code sub} claim)
 * @param displayLabel      display label (first character's name)
 * @param characters        linked game characters
 * @param selectedCharacter index of the selected character
 * @param sessionId         current game session ID
 * @param sessionExpiresAt  session expiration (Unix timestamp)
 */
public record BwuJagexAccount(
        String uuid,
        String subject,
        String displayLabel,
        List<BwuJagexCharacter> characters,
        int selectedCharacter,
        String sessionId,
        long sessionExpiresAt
) {
    // uuid:               char[64]              offset 0
    // subject:            char[256]             offset 64
    // display_label:      char[256]             offset 320
    // characters:         BwuJagexCharacter[16] offset 576    (576 * 16 = 9216 bytes)
    // character_count:    u32                   offset 9792
    // selected_character: i32                   offset 9796
    // session_id:         char[512]             offset 9800
    // session_expires_at: i64                   offset 10312

    private static final long CHARACTERS_OFFSET = 576;
    private static final long CHARACTER_SIZE = BwuLayouts.BWU_JAGEX_CHARACTER.byteSize(); // 576

    static BwuJagexAccount read(MemorySegment seg) {
        int characterCount = seg.get(ValueLayout.JAVA_INT, 9792);
        List<BwuJagexCharacter> chars = new ArrayList<>(characterCount);
        for (int i = 0; i < characterCount; i++) {
            long off = CHARACTERS_OFFSET + (long) i * CHARACTER_SIZE;
            chars.add(BwuJagexCharacter.read(seg.asSlice(off, CHARACTER_SIZE)));
        }

        return new BwuJagexAccount(
                BwuLayouts.readString(seg, 0),
                BwuLayouts.readString(seg, 64),
                BwuLayouts.readString(seg, 320),
                List.copyOf(chars),
                seg.get(ValueLayout.JAVA_INT, 9796),
                BwuLayouts.readString(seg, 9800),
                seg.get(ValueLayout.JAVA_LONG, 10312)
        );
    }
}
