package com.botwithus.bot.core.loader;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * BotWithUs authenticated user details.
 *
 * @param id             user ID
 * @param name           username / display name
 * @param sessionLimit   maximum concurrent sessions
 * @param expirationTs   membership expiration (Unix timestamp)
 */
public record BwuUser(String id, String name, long sessionLimit, long expirationTs) {

    // id:     char[64]   offset 0
    // name:   char[256]  offset 64
    // session_limit: u64 offset 320
    // expiration_ts: u64 offset 328

    static BwuUser read(MemorySegment seg) {
        return new BwuUser(
                BwuLayouts.readString(seg, 0),
                BwuLayouts.readString(seg, 64),
                seg.get(ValueLayout.JAVA_LONG, 320),
                seg.get(ValueLayout.JAVA_LONG, 328)
        );
    }
}
