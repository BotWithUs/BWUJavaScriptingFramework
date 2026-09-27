package com.botwithus.bot.cli.gui.pages.settings;

import imgui.type.ImString;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RowEditsTest {

    private static final String ID = "row:defaultTimeout";

    private final RowEdits edits = new RowEdits();

    @Test
    void anIdleBoxFollowsTheStoredValue() {
        edits.buffer(ID, "10000");

        assertEquals("2500", edits.buffer(ID, "2500").get());
    }

    @Test
    void aBoxBeingTypedInKeepsWhatIsTyped() {
        ImString buffer = edits.buffer(ID, "10000");
        buffer.set("25");
        edits.track(ID, true);

        assertEquals("25", edits.buffer(ID, "10000").get());
    }

    @Test
    void aRefusedValueStaysWithItsReasonUntilAnEditIsTaken() {
        ImString buffer = edits.buffer(ID, "10000");
        buffer.set("abc");
        edits.accept(ID, new EditResult.Refused("Must be a whole number."));

        assertEquals("abc", edits.buffer(ID, "10000").get());
        assertEquals(Optional.of("Must be a whole number."), edits.error(ID));

        edits.accept(ID, new EditResult.Applied());

        assertEquals(Optional.empty(), edits.error(ID));
        assertEquals("10000", edits.buffer(ID, "10000").get());
    }
}
