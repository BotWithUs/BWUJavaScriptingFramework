package com.botwithus.bot.core.test;

import com.botwithus.bot.core.pipe.AgentSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * {@link LiveAgent}'s JUnit verdicts and its wiring to the real environment.
 * The rule itself is {@code AgentPinTest}'s. Nothing here opens a pipe; the
 * environment tests read pipe <em>names</em> only.
 */
class LiveAgentTest {

    /** Set only by the {@code liveAgentPinTest} task, to the pin it passes. */
    static final String EXPECTED_PIN_MARKER = "botwithus.test.expectedPin";
    private static final String HARNESS_CLIENT = "BotWithUs_4100";
    private static final long HARNESS_PID = 4100L;

    // ------------------------------------------------------- the JUnit verdicts

    @Test
    void orSkip_refusedFailsAndIsNeverMaskedAsASkip() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> LiveAgent.orSkip(new AgentSelection.Refused("two clients")));

        assertEquals("two clients", failure.getMessage());
    }

    @Test
    void orSkip_noAgentSkips() {
        TestAbortedException skip = assertThrows(TestAbortedException.class,
                () -> LiveAgent.orSkip(new AgentSelection.NoAgent("nothing up")));

        assertTrue(skip.getMessage().contains("nothing up"), skip.getMessage());
    }

    @Test
    void orFail_noAgentFailsRatherThanSkipping() {
        assertThrows(AssertionFailedError.class, () -> LiveAgent.orFail(new AgentSelection.NoAgent("nothing up")));
    }

    @Test
    void orFail_refusedFails() {
        assertThrows(AssertionFailedError.class, () -> LiveAgent.orFail(new AgentSelection.Refused("two clients")));
    }

    @Test
    void orSkipAndOrFail_returnTheAttachUnchanged() {
        AgentSelection.Attach attach = new AgentSelection.Attach(HARNESS_PID, HARNESS_CLIENT);

        assertSame(attach, LiveAgent.orSkip(attach));
        assertSame(attach, LiveAgent.orFail(attach));
    }

    // ------------------------------------------------------- the entry points, injected

    /*
     * The entry points over an injected property lookup and pipe listing. These
     * decide on every machine and in every test task, whatever client happens to
     * be running: the no-agent branch is driven by an empty stub, not by hoping
     * the machine has no agent up. This is what makes the two delegate swaps
     * (attachOrFail to orSkip, the masking one, and attachOrSkip to orFail)
     * fail locally rather than only on CI.
     */

    private static final UnaryOperator<String> NO_PIN = name -> null;
    private static final Supplier<List<String>> NO_PIPES = List::of;
    private static final UnaryOperator<String> IMPOSSIBLE_PIN =
            name -> LiveAgent.PID_PROPERTY.equals(name) ? "4294967291" : null;

    @Test
    void attachOrFail_noAgent_fails() {
        assertThrows(AssertionFailedError.class, () -> LiveAgent.attachOrFail(NO_PIN, NO_PIPES));
    }

    @Test
    void attachOrSkip_noAgent_skips() {
        assertThrows(TestAbortedException.class, () -> LiveAgent.attachOrSkip(NO_PIN, NO_PIPES));
    }

    @Test
    void bothEntryPoints_refusal_fail_neverSkip() {
        assertThrows(AssertionFailedError.class, () -> LiveAgent.attachOrFail(IMPOSSIBLE_PIN, NO_PIPES));
        assertThrows(AssertionFailedError.class, () -> LiveAgent.attachOrSkip(IMPOSSIBLE_PIN, NO_PIPES));
    }

    @Test
    void bothEntryPoints_loneAgent_attach() {
        Supplier<List<String>> lone = () -> List.of(HARNESS_CLIENT);

        assertEquals(HARNESS_PID, LiveAgent.attachOrFail(NO_PIN, lone).pid());
        assertEquals(HARNESS_PID, LiveAgent.attachOrSkip(NO_PIN, lone).pid());
    }

    // ------------------------------------------------------- the no-arg wiring, against this machine

    /**
     * Pins the no-arg entry points' wiring through the real environment. It
     * decides only on a machine with no agent and no pin, which is CI; the
     * injected tests above cover the same branch everywhere. On a machine where
     * an agent is up or a pin is set it has nothing to decide and aborts.
     */
    @Test
    void entryPoints_withNoAgentAndNoPin_failAndSkipRespectively() {
        switch (LiveAgent.fromEnvironment()) {
            case AgentSelection.NoAgent none -> {
                assertThrows(AssertionFailedError.class, LiveAgent::attachOrFail);
                assertThrows(TestAbortedException.class, LiveAgent::attachOrSkip);
            }
            case AgentSelection.Attach attach -> abort("an agent is visible here (" + attach.pipeName()
                    + "), so the no-agent verdicts cannot be checked on this machine");
            case AgentSelection.Refused refused -> abort("selection refused here, so the no-agent verdicts "
                    + "cannot be checked on this machine: " + refused.reason());
        }
    }

    /**
     * Pins that both entry points fail a refusal through the real environment.
     * It decides whenever the environment refuses, which the {@code
     * liveAgentPinTest} task arranges on every build by pinning a pid no
     * process has.
     */
    @Test
    void entryPoints_withARefusal_bothFail() {
        switch (LiveAgent.fromEnvironment()) {
            case AgentSelection.Refused refused -> {
                assertThrows(AssertionFailedError.class, LiveAgent::attachOrFail);
                assertThrows(AssertionFailedError.class, LiveAgent::attachOrSkip, "a refusal must never skip");
            }
            case AgentSelection.NoAgent none -> abort("nothing refused here: " + none.reason());
            case AgentSelection.Attach attach -> abort("an agent was chosen here: " + attach.pipeName());
        }
    }

    /**
     * Fails the {@code liveAgentPinTest} task if its pin stops arriving. That
     * task sets {@value #EXPECTED_PIN_MARKER} to the pin it also passes; without
     * this, a lost pin would disable the forwarding test and abort the refusal
     * test, and the task would still pass.
     */
    @Test
    @EnabledIfSystemProperty(named = EXPECTED_PIN_MARKER, matches = ".+")
    void pinTask_receivesThePinItPromised() {
        assertEquals(System.getProperty(EXPECTED_PIN_MARKER), System.getProperty(LiveAgent.PID_PROPERTY),
                "the pin task did not pass " + LiveAgent.PID_PROPERTY + " through to the test JVM");
    }

    /**
     * Proves the Gradle task forwarded {@value LiveAgent#PID_PROPERTY} into the
     * test JVM, and that the real {@link LiveAgent#fromEnvironment()} honours
     * it. It runs only when the property arrived, so the proof is seeing it pass
     * rather than skip. Whatever is running, the only acceptable answers are the
     * forwarded pid or a refusal naming it; never another client and never "no
     * agent". The {@code liveAgentPinTest} task runs it on every build with a
     * pid no process has, where the answer must be a refusal.
     */
    @Test
    @EnabledIfSystemProperty(named = LiveAgent.PID_PROPERTY, matches = ".+")
    void harnessPid_reachesTheTestJvm() {
        String forwarded = System.getProperty(LiveAgent.PID_PROPERTY).strip();

        switch (LiveAgent.fromEnvironment()) {
            case AgentSelection.Attach attach -> assertEquals(forwarded, Long.toString(attach.pid()),
                    "attached to a client other than the forwarded pid");
            case AgentSelection.Refused refused -> assertTrue(refused.reason().contains(forwarded),
                    refused.reason());
            case AgentSelection.NoAgent none -> fail("a forwarded pid must never read as unset: " + none.reason());
        }
    }
}
