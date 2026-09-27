package com.botwithus.bot.cli.gui.usermode.board;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionStateTest {

    private static final int FROM = 6;
    private static final int TO = 7;
    private static final SubscriptionState UPDATE = new SubscriptionState.UpdateAvailable(FROM, TO);

    @Test
    void updateAvailable_isChoosable() {
        assertTrue(UPDATE.isChoosable());
    }

    @Test
    void updateAvailable_startsTheInstalledCopyRatherThanInstalling() {
        assertFalse(UPDATE.needsInstall());
    }
}
