package com.botwithus.bot.core.shm;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityIndexWireTest {

    @Test
    void anIndexAboveShortMax_readsAsItsServerIndex() {
        assertEquals(37244, EntityIndexWire.decode((short) 37244));
        assertEquals(65534, EntityIndexWire.decode((short) 65534));
    }

    @Test
    void theSentinel_readsAsNone() {
        assertEquals(EntityIndexWire.NONE, EntityIndexWire.decode((short) -1));
    }

    @Test
    void aLowIndex_isUnchanged() {
        assertEquals(0, EntityIndexWire.decode((short) 0));
        assertEquals(1310, EntityIndexWire.decode((short) 1310));
        assertEquals(Short.MAX_VALUE, EntityIndexWire.decode(Short.MAX_VALUE));
    }

    @Test
    void read_decodesTheFieldAtTheOffset() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(8);
            seg.set(ValueLayout.JAVA_SHORT, 2, (short) 45276);
            seg.set(ValueLayout.JAVA_SHORT, 4, (short) -1);
            assertEquals(45276, EntityIndexWire.read(seg, 2));
            assertEquals(EntityIndexWire.NONE, EntityIndexWire.read(seg, 4));
        }
    }
}
