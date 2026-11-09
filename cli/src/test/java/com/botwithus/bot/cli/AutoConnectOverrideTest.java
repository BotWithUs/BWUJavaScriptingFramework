package com.botwithus.bot.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoConnectOverrideTest {

    @Test
    void false_forcesAutoConnectOff_whateverTheSetting() {
        AutoConnectOverride override = AutoConnectOverride.of("false");

        assertTrue(override.isForcedOff());
        assertFalse(override.allows(true));
        assertFalse(override.allows(false));
    }

    @Test
    void falseIsCaseAndSpaceInsensitive() {
        assertTrue(AutoConnectOverride.of(" FALSE ").isForcedOff());
    }

    @Test
    void unsetOrAnyOtherValue_leavesTheSettingInCharge() {
        for (String value : new String[] {null, "", "true", "off", "0"}) {
            AutoConnectOverride override = AutoConnectOverride.of(value);
            assertFalse(override.isForcedOff(), "value " + value);
            assertTrue(override.allows(true), "value " + value);
            assertFalse(override.allows(false), "value " + value);
        }
    }

    @Test
    void none_isNotForcedOff() {
        assertFalse(AutoConnectOverride.none().isForcedOff());
    }
}
