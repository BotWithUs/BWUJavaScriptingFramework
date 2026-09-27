package com.botwithus.bot.core.worldwalker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pins the wire values shared with the WorldWalker executor. */
class ChainStepKindTest {

    /** The first wire value past the last kind the executor defines. */
    private static final int FIRST_UNKNOWN_WIRE = 7;

    /** The executor's DialogueAnswer, which this host does not handle yet. */
    private static final int DIALOGUE_ANSWER_WIRE = 5;

    @Test
    void fromWire_roundTripsEveryKind() {
        for (ChainStepKind kind : ChainStepKind.values()) {
            assertEquals(kind, ChainStepKind.fromWire(kind.wire()));
        }
    }

    @Test
    void clickNpc_isWireSix() {
        assertEquals(6, ChainStepKind.CLICK_NPC.wire());
        assertEquals(ChainStepKind.CLICK_NPC, ChainStepKind.fromWire(6));
    }

    @Test
    void fromWire_unmappedDialogueAnswer_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ChainStepKind.fromWire(DIALOGUE_ANSWER_WIRE));
    }

    @Test
    void fromWire_unknownValue_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ChainStepKind.fromWire(FIRST_UNKNOWN_WIRE));
    }
}
