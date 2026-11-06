package com.botwithus.bot.api.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentOpsTest {

    /** A backpack slot's mask with an arrow in it: ops 1, 2, 3, 7, 8 and 10 enabled. */
    private static final int BACKPACK_SLOT_MASK = 0x00EB7D8E;
    private static final int DROP_OP = 8;
    private static final int EXAMINE_OP = 10;
    private static final int DISABLED_OP = 9;

    private static final ComponentOps ARROW_SLOT = new ComponentOps(BACKPACK_SLOT_MASK, List.of(
            new ComponentOp(2, "Wield"),
            new ComponentOp(DROP_OP, "Drop"),
            new ComponentOp(DISABLED_OP, "Hidden op"),
            new ComponentOp(EXAMINE_OP, "Examine")));

    @Test
    void opNamed_returnsTheOpNumberNotTheListPosition() {
        assertEquals(OptionalInt.of(DROP_OP), ARROW_SLOT.opNamed("Drop"));
    }

    @Test
    void opNamed_ignoresCase() {
        assertEquals(OptionalInt.of(EXAMINE_OP), ARROW_SLOT.opNamed("examine"));
    }

    @Test
    void opNamed_skipsAnOpTheMaskDisables() {
        assertEquals(OptionalInt.empty(), ARROW_SLOT.opNamed("Hidden op"));
    }

    @Test
    void opNamed_unknownOrNullName_isEmpty() {
        assertEquals(OptionalInt.empty(), ARROW_SLOT.opNamed("Eat"));
        assertEquals(OptionalInt.empty(), ARROW_SLOT.opNamed(null));
    }

    @Test
    void isEnabled_readsBitNForOpN() {
        assertTrue(ARROW_SLOT.isEnabled(1));
        assertTrue(ARROW_SLOT.isEnabled(DROP_OP));
        assertFalse(ARROW_SLOT.isEnabled(DISABLED_OP));
    }

    @Test
    void isEnabled_outsideOneToTen_isFalse() {
        ComponentOps all = new ComponentOps(-1, List.of());
        assertFalse(all.isEnabled(0));
        assertFalse(all.isEnabled(ComponentOps.MAX_OP + 1));
        assertTrue(all.isEnabled(ComponentOps.MAX_OP));
    }

    @Test
    void none_offersNothing() {
        assertEquals(OptionalInt.empty(), ComponentOps.NONE.opNamed("Drop"));
    }
}
