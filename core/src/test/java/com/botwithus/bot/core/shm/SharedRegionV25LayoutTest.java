package com.botwithus.bot.core.shm;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Where {@link SharedRegion} cuts the mapping at v25, the first version where
 * {@code sizeof(Snapshot)} (469192) is not a multiple of 64. The buffers sit a rounded stride
 * (469248) apart, so a reader stepping by {@code snapshotSize} lands 56 bytes short and reads
 * plausible wrong data. Every number is a literal from NXTLibrary's SharedLayout.h at 412510a
 * ({@code kSnapshotStride}, {@code kSnapshotOff1}, {@code kRingOff}). The header publishes
 * {@code ringSize} as {@code sizeof(EventRing)}, so the host's total stops 32 bytes short of the
 * producer's rounded {@code kRegionSize} (1069696), which holds nothing.
 */
class SharedRegionV25LayoutTest {

    private static final int SNAPSHOT_SIZE = 469192;
    private static final int STRIDE = 469248;
    private static final int OFF0 = 64;
    private static final int OFF1 = 469312;
    private static final int RING_OFF = 938560;
    private static final int RING_SIZE = 131104;
    private static final int TOTAL = 1069664;

    private static final int SLOT_0_MARK = 0x11111111;
    private static final int SLOT_1_MARK = 0x22222222;
    private static final int RING_MARK = 0x33333333;

    @Test
    void theLiteralsAgreeWithEachOther() {
        assertEquals(OFF0 + STRIDE, OFF1);
        assertEquals(OFF1 + STRIDE, RING_OFF);
        assertEquals(RING_OFF + RING_SIZE, TOTAL);
        assertEquals(SNAPSHOT_SIZE, Layout.SNAPSHOT_SIZE);
        assertNotEquals(STRIDE, SNAPSHOT_SIZE, "the case this class exists for");
    }

    @Test
    void readLayout_takesEveryOffsetFromTheHeader() {
        try (Arena arena = Arena.ofConfined()) {
            SharedRegion.RegionLayout layout = SharedRegion.readLayout(mapping(arena));

            assertEquals(OFF0, layout.snapshotOff0());
            assertEquals(OFF1, layout.snapshotOff1());
            assertEquals(SNAPSHOT_SIZE, layout.snapshotSize());
            assertEquals(RING_OFF, layout.ringOff());
            assertEquals(RING_SIZE, layout.ringSize());
            assertEquals(TOTAL, layout.totalSize());
        }
    }

    @Test
    void sliced_cutsEachBufferWhereTheProducerWroteIt() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mapping = mapping(arena);
            SharedRegion region = SharedRegion.sliced(mapping, SharedRegion.readLayout(mapping));

            assertEquals(SLOT_0_MARK, region.snapshot0().get(ValueLayout.JAVA_INT, 0));
            assertEquals(SLOT_1_MARK, region.snapshot1().get(ValueLayout.JAVA_INT, 0));
            assertEquals(RING_MARK, region.ring().get(ValueLayout.JAVA_INT, 0));
            assertEquals(SNAPSHOT_SIZE, region.snapshot1().byteSize());
        }
    }

    /** The slot the reader actually uses when the producer has flipped to buffer 1. */
    @Test
    void currentSnapshot_followsFrontIdxToSlotOne() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mapping = mapping(arena);
            mapping.set(ValueLayout.JAVA_INT, Layout.HEADER_FRONTIDX_OFFSET, 1);
            SharedRegion region = SharedRegion.sliced(mapping, SharedRegion.readLayout(mapping));

            assertEquals(SLOT_1_MARK, region.currentSnapshot().get(ValueLayout.JAVA_INT, 0));
        }
    }

    private static MemorySegment mapping(Arena arena) {
        MemorySegment mapping = arena.allocate(TOTAL, Long.BYTES);
        mapping.set(ValueLayout.JAVA_INT, Layout.HEADER_SNAPSHOTSIZE_OFFSET, SNAPSHOT_SIZE);
        mapping.set(ValueLayout.JAVA_INT, Layout.HEADER_SNAPSHOTOFF0_OFFSET, OFF0);
        mapping.set(ValueLayout.JAVA_INT, Layout.HEADER_SNAPSHOTOFF1_OFFSET, OFF1);
        mapping.set(ValueLayout.JAVA_INT, Layout.HEADER_RINGOFF_OFFSET, RING_OFF);
        mapping.set(ValueLayout.JAVA_INT, Layout.HEADER_RINGSIZE_OFFSET, RING_SIZE);
        mapping.set(ValueLayout.JAVA_INT, OFF0, SLOT_0_MARK);
        mapping.set(ValueLayout.JAVA_INT, OFF1, SLOT_1_MARK);
        mapping.set(ValueLayout.JAVA_INT, RING_OFF, RING_MARK);
        return mapping;
    }
}
