package com.botwithus.bot.core.loader;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Notification of a game process starting or exiting.
 *
 * @param pid       process ID
 * @param eventType event type (start/exit)
 */
public record BwuProcessEvent(int pid, BwuProcessEventType eventType) {

    // pid:        u32  offset 0
    // event_type: u32  offset 4

    static BwuProcessEvent read(MemorySegment seg) {
        return new BwuProcessEvent(
                seg.get(ValueLayout.JAVA_INT, 0),
                BwuProcessEventType.fromValue(seg.get(ValueLayout.JAVA_INT, 4))
        );
    }
}
