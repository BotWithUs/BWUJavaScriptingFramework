package com.botwithus.bot.core.loader;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;

/**
 * Parameters passed to the module when loading into a game process.
 *
 * @param pin       bank PIN
 * @param email     account email/username
 * @param password  account password
 * @param uuid      account UUID (auto-generated if empty)
 * @param worldA    free-to-play world
 * @param worldB    members world
 * @param autoLogin auto-login enabled
 */
public record BwuLoadParams(
        String pin,
        String email,
        String password,
        String uuid,
        int worldA,
        int worldB,
        boolean autoLogin
) {
    // pin:        char[64]   offset 0
    // email:      char[512]  offset 64
    // password:   char[512]  offset 576
    // uuid:       char[64]   offset 1088
    // world_a:    u32        offset 1152
    // world_b:    u32        offset 1156
    // auto_login: i32        offset 1160

    MemorySegment writeTo(SegmentAllocator allocator) {
        MemorySegment seg = allocator.allocate(BwuLayouts.BWU_LOAD_PARAMS);
        seg.fill((byte) 0);
        BwuLayouts.writeString(seg, 0, 64, pin);
        BwuLayouts.writeString(seg, 64, 512, email);
        BwuLayouts.writeString(seg, 576, 512, password);
        BwuLayouts.writeString(seg, 1088, 64, uuid);
        seg.set(ValueLayout.JAVA_INT, 1152, worldA);
        seg.set(ValueLayout.JAVA_INT, 1156, worldB);
        seg.set(ValueLayout.JAVA_INT, 1160, autoLogin ? 1 : 0);
        return seg;
    }
}
