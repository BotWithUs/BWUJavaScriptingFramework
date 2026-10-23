package com.botwithus.bot.core.worldwalker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pins the wire values shared with the WorldWalker executor. */
class ChainStepKindTest {

    /** The first wire value past the last kind the executor defines. */
    private static final int FIRST_UNKNOWN_WIRE = 7;

    @Test
    void fromWire_roundTripsEveryKind() {
        for (ChainStepKind kind : ChainStepKind.values()) {
            assertEquals(kind, ChainStepKind.fromWire(kind.wire()));
        }
    }

    @Test
    void dialogueAnswer_isWireFive() {
        assertEquals(5, ChainStepKind.DIALOGUE_ANSWER.wire());
        assertEquals(ChainStepKind.DIALOGUE_ANSWER, ChainStepKind.fromWire(5));
    }

    @Test
    void clickNpc_isWireSix() {
        assertEquals(6, ChainStepKind.CLICK_NPC.wire());
        assertEquals(ChainStepKind.CLICK_NPC, ChainStepKind.fromWire(6));
    }

    @Test
    void fromWire_unknownValue_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ChainStepKind.fromWire(FIRST_UNKNOWN_WIRE));
    }
}
