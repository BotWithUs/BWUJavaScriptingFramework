package com.botwithus.bot.api.draw;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Aggregation of batch outcomes across the batches a large frame is split into.
 *
 * <p>The wire carries the <b>first</b> error string only, never one per item, so
 * merging has to preserve which "first" it means: the earliest refusal in
 * submission order, not the last one seen.</p>
 */
class DrawBatchResultTest {

    @Test
    void empty_isComplete() {
        assertAll(
                () -> assertTrue(DrawBatchResult.EMPTY.isComplete()),
                () -> assertEquals(0, DrawBatchResult.EMPTY.submitted()),
                () -> assertEquals("", DrawBatchResult.EMPTY.firstError()));
    }

    @Test
    void anythingDropped_isNotComplete() {
        DrawBatchResult result = new DrawBatchResult(9, 1, "full");
        assertAll(
                () -> assertFalse(result.isComplete()),
                () -> assertEquals(10, result.submitted()));
    }

    @Test
    void nullError_normalisesToEmptySoCallersNeverSeeANull() {
        assertEquals("", new DrawBatchResult(1, 0, null).firstError());
    }

    @Test
    void merge_sumsCountsAndKeepsTheEarliestError() {
        DrawBatchResult merged = new DrawBatchResult(200, 1, "first")
                .merge(new DrawBatchResult(50, 2, "second"));

        assertAll(
                () -> assertEquals(250, merged.applied()),
                () -> assertEquals(3, merged.dropped()),
                () -> assertEquals("first", merged.firstError()));
    }

    @Test
    void merge_takesTheLaterErrorWhenTheEarlierBatchHadNone() {
        DrawBatchResult merged = new DrawBatchResult(256, 0, "")
                .merge(new DrawBatchResult(0, 1, "store full"));

        assertAll(
                () -> assertEquals("store full", merged.firstError()),
                () -> assertFalse(merged.isComplete()));
    }
}
