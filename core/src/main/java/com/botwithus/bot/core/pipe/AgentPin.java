package com.botwithus.bot.core.pipe;

import com.botwithus.bot.core.shm.SharedRegion;

import java.util.List;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.LongPredicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The one rule for which injected agent a process may attach to, shared by the
 * host's auto-connect, its developer entry points and the live tests.
 *
 * <p>A test harness or a developer runs beside a person's own game, so "the
 * first {@code BotWithUs_<pid>} pipe" can be that person's client. The rule:</p>
 * <ol>
 *   <li>With {@value #PID_PROPERTY} set, that pid's pipe and nothing else. If it
 *       has no pipe, refuse; never fall back to another client.</li>
 *   <li>Without it, a lone visible agent is fine to attach to.</li>
 *   <li>Without it and with two or more visible, refuse: picking one of two is
 *       never right.</li>
 *   <li>Without it and with none visible, {@link AgentSelection.NoAgent}.</li>
 * </ol>
 *
 * <p><b>Set means present.</b> Only an absent property is "unpinned". A property
 * that is present but blank or not a pid (for example
 * {@code -Dbotwithus.harness.pid=$PID} with {@code $PID} unset) is a pin that
 * names no client, so it fails closed: {@link #select} refuses and
 * {@link #pinFilter} allows no pid. Treating blank as unset would turn a shell
 * typo into "every client on the machine".</p>
 *
 * <p>The host's own auto-connect deliberately connects to <em>every</em> agent,
 * which is the product feature. It honours only rule 1, through
 * {@link #scannerFromEnvironment()}: unpinned it scans everything as before,
 * pinned it sees one pid.</p>
 *
 * <p>Only names that parse as {@code BotWithUs_<decimal pid>} count as agents.
 * The pipe scan is a case-insensitive prefix match and also returns names that
 * are not clients.</p>
 */
public final class AgentPin {

    /** Names the one client a harness run, or a pinned developer run, is scoped to. */
    public static final String PID_PROPERTY = "botwithus.harness.pid";

    private AgentPin() {
    }

    /** Applies the rule to this JVM's {@value #PID_PROPERTY} and the live pipe namespace. */
    public static AgentSelection fromEnvironment() {
        return fromEnvironment(System::getProperty, AgentPin::visiblePipes);
    }

    /**
     * The agent pipe names visible right now. Names only: nothing is opened, and
     * this chooses nothing. Feed it to {@link #fromEnvironment(UnaryOperator, Supplier)}.
     */
    public static List<String> visiblePipes() {
        return PipeClient.scanPipes(PipeClient.NAME_PREFIX);
    }

    /**
     * Applies the rule to a property source and a pipe listing.
     *
     * @param properties looks up a system property by name, {@code null} when unset
     * @param pipeScan   lists visible pipe names; only names are read, nothing is opened
     */
    public static AgentSelection fromEnvironment(UnaryOperator<String> properties, Supplier<List<String>> pipeScan) {
        return select(properties.apply(PID_PROPERTY), pipeScan.get());
    }

    /**
     * Applies the rule to the given inputs. Pure.
     *
     * @param pinnedPid    the {@value #PID_PROPERTY} value, or {@code null} when unset
     * @param visiblePipes pipe names as {@link PipeClient#scanPipes(String)} returns them
     */
    public static AgentSelection select(String pinnedPid, List<String> visiblePipes) {
        List<String> agents = visiblePipes.stream().filter(AgentPin::isAgentPipe).toList();
        if (pinnedPid != null) {
            return selectPinned(pinnedPid.strip(), agents);
        }
        return selectUnpinned(agents);
    }

    /**
     * The scanner the host's auto-connect uses: every pipe the prefix finds when
     * {@value #PID_PROPERTY} is absent, exactly as before; only that pid's pipe
     * when it names one; nothing when it is present but blank or not a pid.
     */
    public static Function<String, List<String>> scannerFromEnvironment() {
        return keeping(pinFilter(System.getProperty(PID_PROPERTY)), PipeClient::scanPipes);
    }

    /**
     * A live pipe scanner that reports only agent pipes whose pid passes
     * {@code allowedPid}: for a caller that knows which clients are its own,
     * such as a test that launched them.
     */
    public static Function<String, List<String>> scannerKeeping(LongPredicate allowedPid) {
        return keeping(allowedPid, PipeClient::scanPipes);
    }

    /**
     * Wraps a pipe scanner so it reports only agent pipes whose pid passes
     * {@code allowedPid}. Pure in what it adds: the filtering is all here.
     */
    public static Function<String, List<String>> keeping(LongPredicate allowedPid,
                                                        Function<String, List<String>> scanner) {
        return prefix -> scanner.apply(prefix).stream()
                .filter(name -> SharedRegion.parsePid(name).stream().anyMatch(allowedPid))
                .toList();
    }

    /**
     * Which pids a scanner may report for a {@value #PID_PROPERTY} value: all of
     * them when it is absent ({@code null}), the one named, or none when it is
     * present but blank or names no pid. Agrees with {@link #select}.
     */
    public static LongPredicate pinFilter(String pinnedPid) {
        if (pinnedPid == null) {
            return pid -> true;
        }
        OptionalLong pinned = parsePin(pinnedPid.strip());
        return pid -> pinned.isPresent() && pinned.getAsLong() == pid;
    }

    private static AgentSelection selectPinned(String pinnedPid, List<String> agents) {
        if (pinnedPid.isEmpty()) {
            return new AgentSelection.Refused(PID_PROPERTY + " is set but empty (an unset shell variable?),"
                    + " so it names no client. Pass the game client's decimal pid, or leave the property out.");
        }
        OptionalLong pid = parsePin(pinnedPid);
        if (pid.isEmpty()) {
            return new AgentSelection.Refused(PID_PROPERTY + "=" + pinnedPid
                    + " is not a process id. Pass the game client's decimal pid.");
        }
        long wanted = pid.getAsLong();
        return agents.stream()
                .filter(name -> SharedRegion.parsePid(name).getAsLong() == wanted)
                .findFirst()
                .<AgentSelection>map(name -> new AgentSelection.Attach(wanted, name))
                .orElseGet(() -> new AgentSelection.Refused("No agent pipe "
                        + SharedRegion.PIPE_PREFIX + wanted + " for " + PID_PROPERTY + "=" + wanted
                        + ". Visible agent pipes: " + agents + ". Not falling back to another"
                        + " client: inject the agent into pid " + wanted + " or correct the pid."));
    }

    private static AgentSelection selectUnpinned(List<String> agents) {
        if (agents.isEmpty()) {
            return new AgentSelection.NoAgent("no " + SharedRegion.PIPE_PREFIX
                    + "<pid> pipe visible. Inject the agent into a running client.");
        }
        if (agents.size() > 1) {
            return new AgentSelection.Refused(agents.size() + " agent pipes are visible "
                    + agents + " and " + PID_PROPERTY + " is unset, so which client to drive"
                    + " would be a guess. Pass -D" + PID_PROPERTY + "=<pid>.");
        }
        String only = agents.getFirst();
        return new AgentSelection.Attach(SharedRegion.parsePid(only).getAsLong(), only);
    }

    private static boolean isAgentPipe(String name) {
        return SharedRegion.parsePid(name).isPresent();
    }

    /** A pin is valid exactly when it would form a parsable agent pipe name. */
    private static OptionalLong parsePin(String pinnedPid) {
        return SharedRegion.parsePid(SharedRegion.PIPE_PREFIX + pinnedPid);
    }
}
