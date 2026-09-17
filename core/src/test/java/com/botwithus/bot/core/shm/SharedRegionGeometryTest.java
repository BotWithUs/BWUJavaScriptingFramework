package com.botwithus.bot.core.shm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises {@link SharedRegion#validateGeometry} (H4) — the host-side check
 * that rejects a producer-published mapping geometry inconsistent with the
 * compiled {@link Layout}. Mirrors {@link SharedRegionTargetPidTest}'s use of
 * the package-private validation seam.
 */
class SharedRegionGeometryTest {

    private static final long SNAP = Layout.SNAPSHOT_SIZE;
    private static final long OFF0 = Layout.HEADER_SIZE;
    private static final long OFF1 = OFF0 + SNAP;
    private static final long RING_OFF = OFF1 + SNAP;
    private static final long MIN_RING =
            Layout.RING_SLOTS_OFFSET + (long) Layout.EVENT_RING_SLOTS * Layout.EVENT_SLOT_SIZE;
    private static final long TOTAL = RING_OFF + MIN_RING;

    @Test
    void plausibleGeometryPasses() {
        assertDoesNotThrow(() ->
                SharedRegion.validateGeometry(SNAP, OFF0, OFF1, RING_OFF, MIN_RING, TOTAL));
    }

    @Test
    void snapshotSmallerThanCompiledLayoutRejected() {
        assertThrows(SharedMemoryException.class, () ->
                SharedRegion.validateGeometry(SNAP - 1, OFF0, OFF1, RING_OFF, MIN_RING, TOTAL));
    }

    @Test
    void ringSmallerThanCompiledLayoutRejected() {
        long badRing = MIN_RING - 1;
        assertThrows(SharedMemoryException.class, () ->
                SharedRegion.validateGeometry(SNAP, OFF0, OFF1, RING_OFF, badRing, RING_OFF + badRing));
    }

    @Test
    void snapshotSliceOutOfBoundsRejected() {
        long tooSmallTotal = OFF1 + SNAP - 1;
        assertThrows(SharedMemoryException.class, () ->
                SharedRegion.validateGeometry(SNAP, OFF0, OFF1, RING_OFF, MIN_RING, tooSmallTotal));
    }

    @Test
    void overlappingSnapshotsRejected() {
        long overlappingOff1 = OFF0 + 1;
        assertThrows(SharedMemoryException.class, () ->
                SharedRegion.validateGeometry(SNAP, OFF0, overlappingOff1, RING_OFF, MIN_RING, TOTAL));
    }

    @Test
    void implausiblyLargeTotalRejected() {
        long huge = 1L << 40; // 1 TiB — far above the 64 MiB cap
        assertThrows(SharedMemoryException.class, () ->
                SharedRegion.validateGeometry(SNAP, OFF0, OFF1, RING_OFF, huge, RING_OFF + huge));
    }
}
