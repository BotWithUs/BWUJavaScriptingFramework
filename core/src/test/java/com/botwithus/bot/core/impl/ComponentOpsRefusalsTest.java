package com.botwithus.bot.core.impl;

import com.botwithus.bot.core.impl.ComponentOpsRefusals.Reason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentOpsRefusalsTest {

    private static final int BACKPACK_IFACE = 1473;
    private static final int BACKPACK_COMP = 5;
    private static final int SLOT = 3;
    private static final String METHOD_NOT_FOUND = "method not found: get_component_ops";
    private static final String NOT_FOUND = "get_component_ops: not_found";
    private static final String BAD_PARAM = "get_component_ops: bad_param sub";

    @Test
    void classify_readsTheProducersWording() {
        assertEquals(Reason.AGENT_PREDATES_METHOD, ComponentOpsRefusals.classify(METHOD_NOT_FOUND));
        assertEquals(Reason.COMPONENT_NOT_OPEN, ComponentOpsRefusals.classify(NOT_FOUND));
        assertEquals(Reason.OTHER, ComponentOpsRefusals.classify(BAD_PARAM));
        assertEquals(Reason.OTHER, ComponentOpsRefusals.classify(null));
    }

    @Test
    void report_spendsOneWarnPerReason() {
        ComponentOpsRefusals refusals = new ComponentOpsRefusals();

        refusals.report(BACKPACK_IFACE, BACKPACK_COMP, SLOT, NOT_FOUND);
        refusals.report(BACKPACK_IFACE, BACKPACK_COMP, SLOT, NOT_FOUND);

        assertTrue(refusals.hasWarned(Reason.COMPONENT_NOT_OPEN));
        assertFalse(refusals.hasWarned(Reason.AGENT_PREDATES_METHOD),
                "a reason that was never seen has not spent its warning");
    }

    @Test
    void report_otherRefusalsNeverWarn() {
        ComponentOpsRefusals refusals = new ComponentOpsRefusals();

        assertEquals(Reason.OTHER, refusals.report(BACKPACK_IFACE, BACKPACK_COMP, SLOT, BAD_PARAM));

        assertFalse(refusals.hasWarned(Reason.OTHER));
    }
}
