package com.botwithus.bot.core.cache;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code nxt_wme_placement} (16 bytes) against the offsets {@code nxtcache_c.h} asserts at
 * NXTCacheLibrary dev 03d14f1a: area_id [0], element_id [4], world_x [8], world_y [10],
 * plane [12], flags [13], pad [14].
 */
class WmePlacementTest {

    @Test
    void decodesEachRecordAtItsOffsets_withUnsignedShortsAndBytes() {
        ByteBuffer b = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(28).putInt(3612).putShort((short) 3233).putShort((short) 3222)
                .put((byte) 0).put((byte) 0).putShort((short) 0);
        // A second record with values that need the unsigned reads: x 40000, flags 200.
        b.putInt(231).putInt(65000).putShort((short) 40000).putShort((short) 3361)
                .put((byte) 3).put((byte) 200).putShort((short) 0);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(32);
            MemorySegment.copy(MemorySegment.ofArray(b.array()), 0, seg, 0, 32);

            List<WmePlacement> all = WmePlacement.readAll(seg, 2);

            assertEquals(new WmePlacement(28, 3612, 3233, 3222, 0, 0), all.get(0));
            assertEquals(new WmePlacement(231, 65000, 40000, 3361, 3, 200), all.get(1));
        }
    }
}
