package com.botwithus.bot.api.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentOpsTest {

    /** A backpack slot's op-flags word, read live: ops 1, 2, 3, 7, 8 and 10 transmit. */
    private static final int BACKPACK_SLOT_MASK = 0x00EB7D8E;
    private static final int DROP_OP = 8;
    private static final int EXAMINE_OP = 10;
    /** Bit 9 is clear in the mask above: op 9 runs client-side script only. */
    private static final int SCRIPT_ONLY_OP = 9;
    /** Bit 24 is set in the mask above and is not an op. */
    private static final int NON_OP_FLAG_BIT = 24;

    private static final ComponentOps ARROW_SLOT = new ComponentOps(BACKPACK_SLOT_MASK, List.of(
            new ComponentOp(2, "Wield", true),
            new ComponentOp(DROP_OP, "Drop", true),
            new ComponentOp(SCRIPT_ONLY_OP, "Script op", false),
            new ComponentOp(EXAMINE_OP, "Examine", true)));

    @Test
    void opNamed_returnsTheOpNumberNotTheListPosition() {
        assertEquals(OptionalInt.of(DROP_OP), ARROW_SLOT.opNamed("Drop"));
    }

    @Test
    void opNamed_ignoresCase() {
        assertEquals(OptionalInt.of(EXAMINE_OP), ARROW_SLOT.opNamed("examine"));
    }

    /** The client lists it and a player can choose it, so a name lookup finds it too. */
    @Test
    void opNamed_findsAListedOpThatDoesNotTransmit() {
        assertEquals(OptionalInt.of(SCRIPT_ONLY_OP), ARROW_SLOT.opNamed("Script op"));
    }

    @Test
    void opNamed_unknownOrNullName_isEmpty() {
        assertEquals(OptionalInt.empty(), ARROW_SLOT.opNamed("Eat"));
        assertEquals(OptionalInt.empty(), ARROW_SLOT.opNamed(null));
    }

    @Test
    void isTransmitting_readsBitNForOpN() {
        assertTrue(ARROW_SLOT.isTransmitting(1));
        assertTrue(ARROW_SLOT.isTransmitting(DROP_OP));
        assertFalse(ARROW_SLOT.isTransmitting(SCRIPT_ONLY_OP));
    }

    @Test
    void isTransmitting_outsideOneToTen_isFalse() {
        ComponentOps all = new ComponentOps(-1, List.of());
        assertFalse(all.isTransmitting(0));
        assertFalse(all.isTransmitting(ComponentOps.MAX_OP + 1));
        assertFalse(ARROW_SLOT.isTransmitting(NON_OP_FLAG_BIT));
        assertTrue(all.isTransmitting(ComponentOps.MAX_OP));
    }

    @Test
    void none_offersNothing() {
        assertEquals(OptionalInt.empty(), ComponentOps.NONE.opNamed("Drop"));
    }
}
