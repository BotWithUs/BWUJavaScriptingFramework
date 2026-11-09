package com.botwithus.bot.core.test;

import com.botwithus.bot.core.pipe.AgentPin;
import com.botwithus.bot.core.pipe.AgentSelection;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * The one place a live test chooses which injected agent to attach to.
 *
 * <p>A live test drives a real character, and the integration harness runs
 * beside a person's own game, so "the first {@code BotWithUs_<pid>} pipe" can
 * be that person's client. Every live test therefore asks this class, and none
 * scans pipes itself. The rule is {@link AgentPin}'s, shared with the host's
 * auto-connect and developer entry points; this class only turns its verdict
 * into a JUnit one.</p>
 *
 * <ul>
 *   <li>{@link AgentSelection.Attach}: the test proceeds against that agent.</li>
 *   <li>{@link AgentSelection.Refused}: the test <em>fails</em>, from either entry
 *       point. A refusal must never become a skip, or a misconfigured harness
 *       run would report green without having tested its client.</li>
 *   <li>{@link AgentSelection.NoAgent}: {@link #attachOrSkip()} skips and
 *       {@link #attachOrFail()} fails, so each test keeps the verdict it gave
 *       before this class existed.</li>
 * </ul>
 */
public final class LiveAgent {

    /** Set by the integration harness to the one client its run is scoped to. */
    public static final String PID_PROPERTY = AgentPin.PID_PROPERTY;

    private LiveAgent() {
    }

    /**
     * Chooses the agent, failing the test on anything but a clean attach,
     * including when no agent is visible.
     */
    public static AgentSelection.Attach attachOrFail() {
        return orFail(fromEnvironment());
    }

    /**
     * Chooses the agent, skipping the test when no agent is visible and none
     * was named, and failing it when attaching would be a guess.
     */
    public static AgentSelection.Attach attachOrSkip() {
        return orSkip(fromEnvironment());
    }

    /** {@link AgentPin#fromEnvironment()}, named here so live tests need only this class. */
    public static AgentSelection fromEnvironment() {
        return AgentPin.fromEnvironment();
    }

    /** The attach, or a test failure for anything else. */
    static AgentSelection.Attach orFail(AgentSelection selection) {
        return switch (selection) {
            case AgentSelection.Attach attach -> attach;
            case AgentSelection.NoAgent none -> fail(none.reason());
            case AgentSelection.Refused refused -> fail(refused.reason());
        };
    }

    /** The attach; a skip when there is no agent; a failure when attaching would be a guess. */
    static AgentSelection.Attach orSkip(AgentSelection selection) {
        return switch (selection) {
            case AgentSelection.Attach attach -> attach;
            case AgentSelection.NoAgent none -> abort("SKIPPED, not passed: " + none.reason());
            case AgentSelection.Refused refused -> fail(refused.reason());
        };
    }
}
