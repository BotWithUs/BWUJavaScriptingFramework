package com.botwithus.bot.core.loader;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;

/**
 * Classic game account for username/password or launcher-managed login.
 *
 * @param uuid        unique account identifier
 * @param name        account name / email
 * @param password    account password
 * @param pin         bank PIN
 * @param worldA      primary world number
 * @param worldB      secondary world number
 * @param targetType  game target (primary/secondary)
 * @param accountType login method
 * @param autoLogin   auto-login enabled
 * @param autoRestart auto-restart on logout
 */
public record BwuAccount(
        String uuid,
        String name,
        String password,
        String pin,
        int worldA,
        int worldB,
        BwuTargetType targetType,
        BwuAccountType accountType,
        boolean autoLogin,
        boolean autoRestart
) {
    // uuid:         char[64]   offset 0
    // name:         char[256]  offset 64
    // password:     char[512]  offset 320
    // pin:          char[64]   offset 832
    // world_a:      i32        offset 896
    // world_b:      i32        offset 900
    // target_type:  i32        offset 904
    // account_type: i32        offset 908
    // auto_login:   i32        offset 912
    // auto_restart: i32        offset 916

    static BwuAccount read(MemorySegment seg) {
        return new BwuAccount(
                BwuLayouts.readString(seg, 0),
                BwuLayouts.readString(seg, 64),
                BwuLayouts.readString(seg, 320),
                BwuLayouts.readString(seg, 832),
                seg.get(ValueLayout.JAVA_INT, 896),
                seg.get(ValueLayout.JAVA_INT, 900),
                BwuTargetType.fromValue(seg.get(ValueLayout.JAVA_INT, 904)),
                BwuAccountType.fromValue(seg.get(ValueLayout.JAVA_INT, 908)),
                seg.get(ValueLayout.JAVA_INT, 912) != 0,
                seg.get(ValueLayout.JAVA_INT, 916) != 0
        );
    }

    MemorySegment writeTo(SegmentAllocator allocator) {
        MemorySegment seg = allocator.allocate(BwuLayouts.BWU_ACCOUNT);
        seg.fill((byte) 0);
        BwuLayouts.writeString(seg, 0, 64, uuid);
        BwuLayouts.writeString(seg, 64, 256, name);
        BwuLayouts.writeString(seg, 320, 512, password);
        BwuLayouts.writeString(seg, 832, 64, pin);
        seg.set(ValueLayout.JAVA_INT, 896, worldA);
        seg.set(ValueLayout.JAVA_INT, 900, worldB);
        seg.set(ValueLayout.JAVA_INT, 904, targetType.value());
        seg.set(ValueLayout.JAVA_INT, 908, accountType.value());
        seg.set(ValueLayout.JAVA_INT, 912, autoLogin ? 1 : 0);
        seg.set(ValueLayout.JAVA_INT, 916, autoRestart ? 1 : 0);
        return seg;
    }
}
