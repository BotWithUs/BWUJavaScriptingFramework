package com.botwithus.bot.core.shm;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SharedRegionTargetPidTest {

    private static MemorySegment headerWithPid(Arena arena, long targetPid) {
        MemorySegment header = arena.allocate(Layout.HEADER_SIZE);
        header.set(ValueLayout.JAVA_LONG, Layout.HEADER_TARGETPID_OFFSET, targetPid);
        return header;
    }

    @Test
    void matchingPidPasses() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment header = headerWithPid(arena, 4321L);
            assertDoesNotThrow(() -> SharedRegion.validateTargetPid(header, 4321L));
        }
    }

    @Test
    void mismatchedPidRejected() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment header = headerWithPid(arena, 4321L);
            assertThrows(SharedMemoryException.class,
                    () -> SharedRegion.validateTargetPid(header, 9999L));
        }
    }
}
