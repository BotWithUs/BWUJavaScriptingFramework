package com.botwithus.bot.core.test;

import com.botwithus.bot.core.pipe.AgentSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

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

    // ------------------------------------------------------- the entry points, against this machine

    /**
     * Pins which verdict each entry point gives, through the real environment.
     * It decides on a machine with no agent and no pin, which is CI: there,
     * {@code attachOrFail()} must fail and {@code attachOrSkip()} must skip, so
     * swapping either one's delegate fails here. On a machine where an agent is
     * up or a pin is set it has nothing to decide, and says so by aborting.
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
