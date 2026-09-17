package com.botwithus.bot.core.worldwalker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the native-boundary count guard.
 *
 * <p>Unlike {@link WorldWalkerExecutorE2ETest} these need no {@code worldwalker.dll}:
 * {@link WorldWalkerLayouts} holds only layouts and constants, so its class
 * initialiser does not load the library.</p>
 */
class WorldWalkerLayoutsTest {

    @Test
    void acceptsCountsWithinTheCeiling() {
        assertEquals(0, WorldWalkerLayouts.boundedCount(0, 16, "n"));
        assertEquals(7, WorldWalkerLayouts.boundedCount(7, 16, "n"));
        assertEquals(16, WorldWalkerLayouts.boundedCount(16, 16, "n"), "ceiling is inclusive");
    }

    @Test
    void rejectsNegativeCounts() {
        assertThrows(WorldWalkerException.class,
                () -> WorldWalkerLayouts.boundedCount(-1, 16, "n"));
    }

    @Test
    void rejectsCountsAboveTheCeiling() {
        assertThrows(WorldWalkerException.class,
                () -> WorldWalkerLayouts.boundedCount(17, 16, "n"));
    }

    /**
     * The regression this guard exists for: a garbage-but-int-ranged count from
     * a misbehaving native side used to pass the old
     * {@code count > Integer.MAX_VALUE} check and go on to size a multi-GB
     * view.
     */
    @Test
    void rejectsGarbageCountsThatStillFitInAnInt() {
        assertThrows(WorldWalkerException.class, () -> WorldWalkerLayouts.boundedCount(
                Integer.MAX_VALUE, WorldWalkerLayouts.MAX_PATH_STEPS, "ww_query stepCount"));
        assertThrows(WorldWalkerException.class, () -> WorldWalkerLayouts.boundedCount(
                Integer.MAX_VALUE, WorldWalkerLayouts.MAX_BATCH_IDS, "batch count"));
    }

    @Test
    void messageNamesTheCountAndBothNumbers() {
        WorldWalkerException thrown = assertThrows(WorldWalkerException.class,
                () -> WorldWalkerLayouts.boundedCount(99, 16, "ww_query stepCount"));
        String message = thrown.getMessage();
        assertTrue(message.contains("ww_query stepCount"), message);
        assertTrue(message.contains("99"), message);
        assertTrue(message.contains("16"), message);
    }

    /**
     * The ceilings bound the derived allocation, which is the whole point of
     * having them — pin the arithmetic so raising one is a deliberate act.
     */
    @Test
    void ceilingsBoundTheDerivedAllocations() {
        long stepBytes = (long) WorldWalkerLayouts.MAX_PATH_STEPS * WorldWalkerLayouts.WW_STEP.byteSize();
        assertEquals(1L << 20, stepBytes, "steps view capped at 1 MiB");

        long batchBytes = (long) WorldWalkerLayouts.MAX_BATCH_IDS * Integer.BYTES;
        assertEquals(1L << 18, batchBytes, "each batch view capped at 256 KiB");
    }
}
