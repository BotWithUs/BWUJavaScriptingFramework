package com.botwithus.bot.api.dialog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * How a multi-choice row's texts split into the option's index and label.
 *
 * <p>The regression: a quiz answer shaped like a number cell ("4.") was taken
 * for a second index cell, the row was left with no label, and the option was
 * dropped from {@code Dialog.options}, so only the other rows could be chosen.</p>
 */
class DialogCellsTest {

    @Test
    void numberCellThenLabel_givesBoth() {
        Dialog.Cells cells = Dialog.cells(List.of("2.", "Talk about something else."), 9);

        assertEquals(2, cells.index());
        assertEquals("Talk about something else.", cells.label());
    }

    @Test
    void labelShapedLikeANumberCell_isKeptAsTheLabel() {
        Dialog.Cells cells = Dialog.cells(List.of("2.", "4."), 9);

        assertEquals(2, cells.index());
        assertEquals("4.", cells.label());
    }

    @Test
    void noNumberCell_fallsBackToThePosition() {
        Dialog.Cells cells = Dialog.cells(List.of("Yes."), 3);

        assertEquals(3, cells.index());
        assertEquals("Yes.", cells.label());
    }

    @Test
    void numberCellAlone_isAnEmptySlot() {
        assertNull(Dialog.cells(List.of("1."), 1));
    }

    @Test
    void noTexts_isAnEmptySlot() {
        assertNull(Dialog.cells(List.of(), 1));
    }
}
