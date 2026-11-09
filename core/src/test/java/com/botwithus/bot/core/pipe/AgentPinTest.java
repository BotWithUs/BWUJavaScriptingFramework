package com.botwithus.bot.core.pipe;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The shared agent rule, against made-up pipe lists. None of these open a
 * pipe, so they run on any machine, with or without a game client.
 */
class AgentPinTest {

    private static final String HARNESS_CLIENT = "BotWithUs_4100";
    private static final String OTHER_CLIENT = "BotWithUs_5200";
    private static final String TEST_SERVER = "BotWithUs_test_77_1";
    private static final long HARNESS_PID = 4100L;
    private static final long OTHER_PID = 5200L;
    private static final long MISSING_PID = 6300L;

    @Test
    void select_pinnedPidWithAPipe_attachesToThatPipeEvenBesideAnotherClient() {
        AgentSelection.Attach attach = attached(
                AgentPin.select("4100", List.of(OTHER_CLIENT, HARNESS_CLIENT)));

        assertEquals(HARNESS_PID, attach.pid());
        assertEquals(HARNESS_CLIENT, attach.pipeName());
    }

    @Test
    void select_pinnedPidWithSurroundingWhitespace_isTrimmed() {
        assertEquals(HARNESS_PID, attached(AgentPin.select(" 4100\n", List.of(HARNESS_CLIENT))).pid());
    }

    @Test
    void select_pinnedPidWithNoPipe_refusesRatherThanTakingTheOnlyOtherClient() {
        String reason = refused(AgentPin.select("6300", List.of(OTHER_CLIENT)));

        assertTrue(reason.contains("No agent pipe BotWithUs_" + MISSING_PID), reason);
        assertTrue(reason.contains(AgentPin.PID_PROPERTY + "=" + MISSING_PID), reason);
        assertTrue(reason.contains("Not falling back"), reason);
        assertTrue(reason.contains(OTHER_CLIENT), "names what was visible: " + reason);
    }

    @Test
    void select_pinnedPidWithNothingVisible_refusesRatherThanReportingNoAgent() {
        refused(AgentPin.select("6300", List.of()));
    }

    @Test
    void select_pinnedPidThatIsNotANumber_refuses() {
        String reason = refused(AgentPin.select("abc", List.of(HARNESS_CLIENT)));

        assertTrue(reason.contains("is not a process id"), reason);
    }

    @Test
    void select_pinnedPidThatOnlySuffixMatches_doesNotAttach() {
        refused(AgentPin.select("100", List.of(HARNESS_CLIENT)));
    }

    @Test
    void select_unpinnedWithOneClient_attaches() {
        AgentSelection.Attach attach = attached(AgentPin.select(null, List.of(OTHER_CLIENT)));

        assertEquals(OTHER_PID, attach.pid());
        assertEquals(OTHER_CLIENT, attach.pipeName());
    }

    @Test
    void select_blankPropertyIsUnset() {
        assertEquals(OTHER_PID, attached(AgentPin.select("  ", List.of(OTHER_CLIENT))).pid());
    }

    @Test
    void select_unpinnedWithSeveralClients_refusesAndSaysHowToPin() {
        String reason = refused(AgentPin.select(null, List.of(HARNESS_CLIENT, OTHER_CLIENT)));

        assertTrue(reason.contains("2 agent pipes"), reason);
        assertTrue(reason.contains(HARNESS_CLIENT) && reason.contains(OTHER_CLIENT), reason);
        assertTrue(reason.contains("-D" + AgentPin.PID_PROPERTY + "=<pid>"), reason);
    }

    @Test
    void select_unpinnedWithNothingVisible_reportsNoAgent() {
        AgentSelection selection = AgentPin.select(null, List.of());

        switch (selection) {
            case AgentSelection.NoAgent none -> assertTrue(none.reason().contains("no BotWithUs_<pid>"));
            case AgentSelection.Attach attach -> fail("attached to " + attach);
            case AgentSelection.Refused refused -> fail("refused: " + refused.reason());
        }
    }

    @Test
    void select_nonAgentPipesAreNeitherAttachedToNorCountedAsAClient() {
        assertEquals(OTHER_PID, attached(AgentPin.select(null, List.of(TEST_SERVER, OTHER_CLIENT))).pid());
        switch (AgentPin.select(null, List.of(TEST_SERVER))) {
            case AgentSelection.NoAgent none -> assertTrue(none.reason().contains("no "));
            case AgentSelection.Attach attach -> fail("attached to a test server: " + attach);
            case AgentSelection.Refused refused -> fail("refused: " + refused.reason());
        }
    }

    // ------------------------------------------------------- the property lookup

    @Test
    void fromEnvironment_readsTheHarnessPropertyAndRefusesAnImpossiblePid() {
        Map<String, String> properties = Map.of(AgentPin.PID_PROPERTY, Long.toString(MISSING_PID));

        refused(AgentPin.fromEnvironment(properties::get, () -> List.of(OTHER_CLIENT)));
    }

    @Test
    void fromEnvironment_pinnedPidSelectsItsPipeOverTheOtherClient() {
        Map<String, String> properties = Map.of(AgentPin.PID_PROPERTY, Long.toString(HARNESS_PID));

        assertEquals(HARNESS_PID, attached(AgentPin.fromEnvironment(properties::get,
                () -> List.of(OTHER_CLIENT, HARNESS_CLIENT))).pid());
    }

    @Test
    void fromEnvironment_withThePropertyUnsetTakesTheLoneClient() {
        Map<String, String> properties = Map.of("botwithus.some.other.pid", Long.toString(MISSING_PID));

        assertEquals(OTHER_PID, attached(AgentPin.fromEnvironment(properties::get, () -> List.of(OTHER_CLIENT))).pid());
    }

    // ------------------------------------------------------- the auto-connect scanner

    @Test
    void pinFilter_unsetOrBlank_allowsEveryPid() {
        assertTrue(AgentPin.pinFilter(null).test(HARNESS_PID));
        assertTrue(AgentPin.pinFilter("  ").test(OTHER_PID));
    }

    @Test
    void pinFilter_set_allowsOnlyThatPid() {
        LongPredicate pinned = AgentPin.pinFilter(" 4100 ");

        assertTrue(pinned.test(HARNESS_PID));
        assertFalse(pinned.test(OTHER_PID));
    }

    @Test
    void pinFilter_notAPid_allowsNothing() {
        assertFalse(AgentPin.pinFilter("abc").test(HARNESS_PID));
        assertFalse(AgentPin.pinFilter("-4100").test(HARNESS_PID));
    }

    @Test
    void keeping_reportsOnlyAllowedAgentPipes_andPassesThePrefixThrough() {
        List<String> asked = new ArrayList<>();
        Function<String, List<String>> scanner = AgentPin.keeping(pid -> pid == HARNESS_PID, prefix -> {
            asked.add(prefix);
            return List.of(OTHER_CLIENT, TEST_SERVER, HARNESS_CLIENT);
        });

        assertEquals(List.of(HARNESS_CLIENT), scanner.apply("BotWithUs"));
        assertEquals(List.of("BotWithUs"), asked);
    }

    @Test
    void keeping_withAPinFromTheProperty_neverReportsAnotherClient() {
        Function<String, List<String>> scanner = AgentPin.keeping(AgentPin.pinFilter("6300"),
                prefix -> List.of(OTHER_CLIENT, HARNESS_CLIENT));

        assertEquals(List.of(), scanner.apply("BotWithUs"), "a pin with no pipe must find nothing, not the rest");
    }

    @Test
    void keeping_unpinned_reportsEveryAgentPipeAsAutoConnectAlwaysHas() {
        Function<String, List<String>> scanner = AgentPin.keeping(AgentPin.pinFilter(null),
                prefix -> List.of(OTHER_CLIENT, TEST_SERVER, HARNESS_CLIENT));

        assertEquals(List.of(OTHER_CLIENT, HARNESS_CLIENT), scanner.apply("BotWithUs"));
    }

    private static AgentSelection.Attach attached(AgentSelection selection) {
        return switch (selection) {
            case AgentSelection.Attach attach -> attach;
            case AgentSelection.NoAgent none -> fail("expected an attach, got no agent: " + none.reason());
            case AgentSelection.Refused refused -> fail("expected an attach, got: " + refused.reason());
        };
    }

    private static String refused(AgentSelection selection) {
        return switch (selection) {
            case AgentSelection.Refused refused -> refused.reason();
            case AgentSelection.NoAgent none -> fail("expected a refusal, got no agent: " + none.reason());
            case AgentSelection.Attach attach -> fail("expected a refusal, got an attach to " + attach);
        };
    }
}
