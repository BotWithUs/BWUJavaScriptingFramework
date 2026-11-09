package com.botwithus.bot.core.test;

import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.shm.SharedRegion;

import java.util.List;
import java.util.OptionalLong;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * The one place a live test chooses which injected agent to attach to.
 *
 * <p>A live test drives a real character. The integration harness runs beside
 * a person's own game, so "the first {@code BotWithUs_<pid>} pipe" can be that
 * person's client, and a test that took it would click, walk and type on their
 * character. Every live test therefore asks this class, and none scans pipes
 * itself.</p>
 *
 * <p>The rules, in order:</p>
 * <ol>
 *   <li>With {@value #PID_PROPERTY} set, attach to that pid's pipe and nothing
 *       else. If it has no pipe, refuse; never fall back to scanning, because
 *       the fallback is exactly how a test reaches the wrong client.</li>
 *   <li>Without it, attach when exactly one agent pipe is visible, so a
 *       developer running one client by hand needs no flag.</li>
 *   <li>Without it and with two or more visible, refuse. Picking one of two is
 *       never right.</li>
 *   <li>Without it and with none visible, report {@link LiveAgentSelection.NoAgent};
 *       {@link #attachOrSkip()} skips and {@link #attachOrFail()} fails, so each
 *       test keeps the verdict it gave before this class existed.</li>
 * </ol>
 *
 * <p>Only names that parse as {@code BotWithUs_<decimal pid>} count as agents.
 * The pipe scan is a case-insensitive prefix match, so it also returns names
 * such as a test's own {@code BotWithUs_test_...} server, which are not
 * clients and must neither be attached to nor make a lone agent look
 * ambiguous.</p>
 */
public final class LiveAgent {

    /** Set by the integration harness to the one client its run is scoped to. */
    public static final String PID_PROPERTY = "botwithus.harness.pid";

    private LiveAgent() {
    }

    /**
     * Chooses the agent, failing the test on anything but a clean attach,
     * including when no agent is visible.
     */
    public static LiveAgentSelection.Attach attachOrFail() {
        return orFail(fromEnvironment());
    }

    /**
     * Chooses the agent, skipping the test when no agent is visible and none
     * was named, and failing it when attaching would be a guess.
     */
    public static LiveAgentSelection.Attach attachOrSkip() {
        return orSkip(fromEnvironment());
    }

    /** The attach, or a test failure for anything else. */
    public static LiveAgentSelection.Attach orFail(LiveAgentSelection selection) {
        return switch (selection) {
            case LiveAgentSelection.Attach attach -> attach;
            case LiveAgentSelection.NoAgent none -> fail(none.reason());
            case LiveAgentSelection.Refused refused -> fail(refused.reason());
        };
    }

    /**
     * The attach; a skip when there is no agent; a failure when attaching would
     * be a guess. A refusal must never become a skip: a skip would let a
     * misconfigured harness run report green without testing its client.
     */
    public static LiveAgentSelection.Attach orSkip(LiveAgentSelection selection) {
        return switch (selection) {
            case LiveAgentSelection.Attach attach -> attach;
            case LiveAgentSelection.NoAgent none -> abort("SKIPPED, not passed: " + none.reason());
            case LiveAgentSelection.Refused refused -> fail(refused.reason());
        };
    }

    /** Applies the rules to this JVM's {@value #PID_PROPERTY} and the live pipe namespace. */
    public static LiveAgentSelection fromEnvironment() {
        return fromEnvironment(System::getProperty, () -> PipeClient.scanPipes(PipeClient.NAME_PREFIX));
    }

    /**
     * Applies the rules to a property source and a pipe listing, so the
     * property lookup itself is tested without a client.
     *
     * @param properties looks up a system property by name, {@code null} when unset
     * @param pipeScan   lists visible pipe names; only names are read, nothing is opened
     */
    public static LiveAgentSelection fromEnvironment(UnaryOperator<String> properties,
                                                     Supplier<List<String>> pipeScan) {
        return select(properties.apply(PID_PROPERTY), pipeScan.get());
    }

    /**
     * Applies the rules to the given inputs. Pure, so it is tested without a
     * game client.
     *
     * @param pinnedPid    the {@value #PID_PROPERTY} value, or {@code null} when unset
     * @param visiblePipes pipe names as {@link PipeClient#scanPipes(String)} returns them
     */
    public static LiveAgentSelection select(String pinnedPid, List<String> visiblePipes) {
        List<String> agents = visiblePipes.stream()
                .filter(name -> SharedRegion.parsePid(name).isPresent())
                .toList();
        if (pinnedPid != null && !pinnedPid.isBlank()) {
            return selectPinned(pinnedPid.strip(), agents);
        }
        return selectUnpinned(agents);
    }

    private static LiveAgentSelection selectPinned(String pinnedPid, List<String> agents) {
        OptionalLong pid = SharedRegion.parsePid(SharedRegion.PIPE_PREFIX + pinnedPid);
        if (pid.isEmpty()) {
            return new LiveAgentSelection.Refused(PID_PROPERTY + "=" + pinnedPid
                    + " is not a process id. Pass the game client's decimal pid.");
        }
        long wanted = pid.getAsLong();
        return agents.stream()
                .filter(name -> SharedRegion.parsePid(name).getAsLong() == wanted)
                .findFirst()
                .<LiveAgentSelection>map(name -> new LiveAgentSelection.Attach(wanted, name))
                .orElseGet(() -> new LiveAgentSelection.Refused("No agent pipe "
                        + SharedRegion.PIPE_PREFIX + wanted + " for " + PID_PROPERTY + "=" + wanted
                        + ". Visible agent pipes: " + agents + ". Not falling back to another"
                        + " client: inject the agent into pid " + wanted + " or correct the pid."));
    }

    private static LiveAgentSelection selectUnpinned(List<String> agents) {
        if (agents.isEmpty()) {
            return new LiveAgentSelection.NoAgent("no " + SharedRegion.PIPE_PREFIX
                    + "<pid> pipe visible. Inject the agent into a running client.");
        }
        if (agents.size() > 1) {
            return new LiveAgentSelection.Refused(agents.size() + " agent pipes are visible "
                    + agents + " and " + PID_PROPERTY + " is unset, so which client to drive"
                    + " would be a guess. Pass -D" + PID_PROPERTY + "=<pid>.");
        }
        String only = agents.getFirst();
        return new LiveAgentSelection.Attach(SharedRegion.parsePid(only).getAsLong(), only);
    }
}
