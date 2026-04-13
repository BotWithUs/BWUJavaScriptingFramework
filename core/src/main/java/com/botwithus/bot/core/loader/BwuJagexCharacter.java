package com.botwithus.bot.core.loader;

import java.lang.foreign.MemorySegment;

/**
 * A single game character linked to a Jagex account.
 *
 * @param accountId   Jagex character account ID
 * @param displayName in-game character name
 * @param userHash    user hash identifier
 */
public record BwuJagexCharacter(String accountId, String displayName, String userHash) {

    // account_id:   char[64]   offset 0
    // display_name: char[256]  offset 64
    // user_hash:    char[256]  offset 320
    // total size: 576

    static BwuJagexCharacter read(MemorySegment seg) {
        return new BwuJagexCharacter(
                BwuLayouts.readString(seg, 0),
                BwuLayouts.readString(seg, 64),
                BwuLayouts.readString(seg, 320)
        );
    }
}
