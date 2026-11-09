package com.botwithus.bot.core.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The live-test client selector, against made-up pipe lists. None of these
 * open a pipe, so they run on any machine, with or without a game client.
 */
class LiveAgentTest {

    private static final String PIPE_PREFIX = "BotWithUs_";
    private static final String HARNESS_CLIENT = "BotWithUs_4100";
    private static final String OTHER_CLIENT = "BotWithUs_5200";
    private static final String TEST_SERVER = "BotWithUs_test_77_1";
    private static final long HARNESS_PID = 4100L;
    private static final long OTHER_PID = 5200L;
    private static final long MISSING_PID = 6300L;

    @Test
    void select_pinnedPidWithAPipe_attachesToThatPipeEvenBesideAnotherClient() {
        LiveAgentSelection.Attach attach = attached(
                LiveAgent.select("4100", List.of(OTHER_CLIENT, HARNESS_CLIENT)));

        assertEquals(HARNESS_PID, attach.pid());
        assertEquals(HARNESS_CLIENT, attach.pipeName());
    }

    @Test
    void select_pinnedPidWithSurroundingWhitespace_isTrimmed() {
        assertEquals(HARNESS_PID, attached(LiveAgent.select(" 4100\n", List.of(HARNESS_CLIENT))).pid());
    }

    @Test
    void select_pinnedPidWithNoPipe_refusesRatherThanTakingTheOnlyOtherClient() {
        String reason = refused(LiveAgent.select("6300", List.of(OTHER_CLIENT)));

        assertTrue(reason.contains("No agent pipe BotWithUs_" + MISSING_PID), reason);
        assertTrue(reason.contains(LiveAgent.PID_PROPERTY + "=" + MISSING_PID), reason);
        assertTrue(reason.contains("Not falling back"), reason);
        assertTrue(reason.contains(OTHER_CLIENT), "names what was visible: " + reason);
    }

    @Test
    void select_pinnedPidWithNothingVisible_refusesRatherThanReportingNoAgent() {
        refused(LiveAgent.select("6300", List.of()));
    }

    @Test
    void select_pinnedPidThatIsNotANumber_refuses() {
        String reason = refused(LiveAgent.select("abc", List.of(HARNESS_CLIENT)));

        assertTrue(reason.contains("is not a process id"), reason);
    }

    @Test
    void select_pinnedPidThatOnlySuffixMatches_doesNotAttach() {
        refused(LiveAgent.select("100", List.of(HARNESS_CLIENT)));
    }

    @Test
    void select_unpinnedWithOneClient_attaches() {
        LiveAgentSelection.Attach attach = attached(LiveAgent.select(null, List.of(OTHER_CLIENT)));

        assertEquals(OTHER_PID, attach.pid());
        assertEquals(OTHER_CLIENT, attach.pipeName());
    }

    @Test
    void select_blankPropertyIsUnset() {
        assertEquals(OTHER_PID, attached(LiveAgent.select("  ", List.of(OTHER_CLIENT))).pid());
    }

    @Test
    void select_unpinnedWithSeveralClients_refusesAndSaysHowToPin() {
        String reason = refused(LiveAgent.select(null, List.of(HARNESS_CLIENT, OTHER_CLIENT)));

        assertTrue(reason.contains("2 agent pipes"), reason);
        assertTrue(reason.contains(HARNESS_CLIENT) && reason.contains(OTHER_CLIENT), reason);
        assertTrue(reason.contains("-D" + LiveAgent.PID_PROPERTY + "=<pid>"), reason);
    }

    @Test
    void select_unpinnedWithNothingVisible_reportsNoAgent() {
        LiveAgentSelection selection = LiveAgent.select(null, List.of());

        switch (selection) {
            case LiveAgentSelection.NoAgent none -> assertTrue(none.reason().contains("no BotWithUs_<pid>"));
            case LiveAgentSelection.Attach attach -> fail("attached to " + attach);
            case LiveAgentSelection.Refused refused -> fail("refused: " + refused.reason());
        }
    }

    @Test
    void select_nonAgentPipesAreNeitherAttachedToNorCountedAsAClient() {
        assertEquals(OTHER_PID, attached(LiveAgent.select(null, List.of(TEST_SERVER, OTHER_CLIENT))).pid());
        switch (LiveAgent.select(null, List.of(TEST_SERVER))) {
            case LiveAgentSelection.NoAgent none -> assertTrue(none.reason().contains("no "));
            case LiveAgentSelection.Attach attach -> fail("attached to a test server: " + attach);
            case LiveAgentSelection.Refused refused -> fail("refused: " + refused.reason());
        }
    }

    /**
     * Proves the Gradle task forwarded {@value LiveAgent#PID_PROPERTY} into the
     * test JVM, and that what arrived selects that pid's pipe over another
     * client's. It only runs when the property arrived, so the proof is seeing
     * it pass rather than skip. It opens nothing.
     */
    @Test
    @EnabledIfSystemProperty(named = LiveAgent.PID_PROPERTY, matches = ".+")
    void harnessPid_reachesTheTestJvm() {
        String forwarded = System.getProperty(LiveAgent.PID_PROPERTY).strip();

        assertEquals(forwarded, Long.toString(attached(
                LiveAgent.select(forwarded, List.of(OTHER_CLIENT, PIPE_PREFIX + forwarded))).pid()));
    }

    private static LiveAgentSelection.Attach attached(LiveAgentSelection selection) {
        return switch (selection) {
            case LiveAgentSelection.Attach attach -> attach;
            case LiveAgentSelection.NoAgent none -> fail("expected an attach, got no agent: " + none.reason());
            case LiveAgentSelection.Refused refused -> fail("expected an attach, got: " + refused.reason());
        };
    }

    private static String refused(LiveAgentSelection selection) {
        return switch (selection) {
            case LiveAgentSelection.Refused refused -> refused.reason();
            case LiveAgentSelection.NoAgent none -> fail("expected a refusal, got no agent: " + none.reason());
            case LiveAgentSelection.Attach attach -> fail("expected a refusal, got an attach to " + attach);
        };
    }
}
