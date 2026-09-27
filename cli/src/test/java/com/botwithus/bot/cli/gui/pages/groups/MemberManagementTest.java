package com.botwithus.bot.cli.gui.pages.groups;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The words of a member's robot link, from who targets it and how. */
class MemberManagementTest {

    private static final String BREAKS = "Break Scheduler";

    @Test
    void theGroupsOwnManager_saysOwnSettingsOrAlsoDirect_andAnotherScriptIsNamed() {
        MemberManagement own = MemberManagement.of(Optional.of(BREAKS), BREAKS, 2, "Woodcutting");
        MemberManagement direct = MemberManagement.of(Optional.of(BREAKS), BREAKS, 0, "Woodcutting");
        MemberManagement other = MemberManagement.of(Optional.of("World Balancer"), BREAKS, 1, "Divination");
        MemberManagement noManager = MemberManagement.of(Optional.empty(), BREAKS, 0, "Divination");

        assertAll(
                () -> assertEquals("Own settings", own.label()),
                () -> assertEquals("Also direct", direct.label()),
                () -> assertEquals(BREAKS, other.label()),
                () -> assertEquals(BREAKS, noManager.label()),
                () -> assertEquals("Break Scheduler also targets this client's Woodcutting directly, with 2 settings"
                        + " of its own.", own.tooltip()),
                () -> assertEquals(BREAKS, own.managementScript()));
    }
}
