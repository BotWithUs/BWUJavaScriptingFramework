package com.botwithus.bot.core.rpc;

import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.shm.SharedRegion;

import java.util.List;
import java.util.OptionalLong;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

/**
 * Resolves, per reconnect attempt, which pipe a dropped connection may
 * reconnect to — and refuses to answer with anyone else's.
 *
 * <p><b>Why re-resolve at all.</b> The controller used to close over the pipe
 * name captured when the connection was built. A producer pipe is named
 * {@code BotWithUs_<pid>}, so if the game restarted under a new pid that name
 * would never exist again, and with {@code ReconnectPolicy.DEFAULT}'s
 * effectively-unbounded attempt budget the controller retried it forever. The
 * policy's own javadoc claims to cover "a game-client crash that takes 30s to
 * restart" — precisely the case a captured name cannot handle.</p>
 *
 * <p><b>Why only our own pid.</b> Re-resolving invites the opposite mistake:
 * picking whatever {@code BotWithUs_*} pipe happens to be visible. That must
 * not happen. The pid is the connection's identity — the shared-memory
 * snapshot mapping is {@code Local\nxt_snapshot_<pid>}, opened once when the
 * connection was built and owned by {@code SharedRegionEventPump}; the
 * {@code connections} map is keyed by pipe name; {@code ClientImpl} holds the
 * region directly; and running scripts hold entity flyweights over it.
 * Swapping only the RPC transport to a different game would leave every one of
 * those pointing at the dead process, so reads would return plausible,
 * wrong numbers instead of failing. A reconnect that silently serves stale
 * game state is worse than one that fails.</p>
 *
 * <p>Hence: several candidates visible is not an invitation to choose. Only an
 * exact (case-insensitive) match on our own name is {@link PipeResolution.Found};
 * everything else is judged by whether our process is still alive.</p>
 */
public final class SamePidPipeResolver {

    /**
     * Consecutive attempts our pipe may stay missing while the process is
     * still alive before we stop waiting. Bounds two cases the liveness probe
     * alone cannot: an agent that never re-opens its pipe, and a pid recycled
     * by the OS onto an unrelated process (which would otherwise read as
     * "alive" forever). With {@code ReconnectPolicy.DEFAULT}'s backoff this is
     * roughly four minutes — far beyond the ~30s a client restart needs.
     */
    static final int MAX_WAIT_ATTEMPTS = 20;

    private final String pipeName;
    private final long pid;
    private final Supplier<List<String>> pipeScan;
    private final LongPredicate processAlive;

    /**
     * @param pipeName the {@code BotWithUs_<pid>} name this connection was
     *                 built on; its embedded pid becomes the identity we will
     *                 accept and nothing else.
     * @throws IllegalArgumentException if the name carries no pid, which would
     *                                  leave us with no identity to enforce.
     */
    public SamePidPipeResolver(String pipeName) {
        this(pipeName, defaultScan(), pid -> ProcessHandle.of(pid).isPresent());
    }

    SamePidPipeResolver(String pipeName, Supplier<List<String>> pipeScan,
                        LongPredicate processAlive) {
        OptionalLong parsed = SharedRegion.parsePid(pipeName);
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException(
                    "Pipe '" + pipeName + "' has no embedded pid to reconnect against");
        }
        this.pipeName = pipeName;
        this.pid = parsed.getAsLong();
        this.pipeScan = pipeScan;
        this.processAlive = processAlive;
    }

    private static Supplier<List<String>> defaultScan() {
        return () -> PipeClient.scanPipes(PipeClient.NAME_PREFIX);
    }

    /**
     * @param attempt 1-indexed reconnect attempt, used only to bound how long
     *                we wait for a live process to re-open its pipe.
     */
    public PipeResolution resolve(int attempt) {
        if (isOurPipeVisible()) {
            return new PipeResolution.Found(pipeName);
        }
        if (!processAlive.test(pid)) {
            return new PipeResolution.Gone("game process " + pid + " has exited, so pipe '"
                    + pipeName + "' will not return; reconnect to the new game instance");
        }
        if (attempt >= MAX_WAIT_ATTEMPTS) {
            return new PipeResolution.Gone("process " + pid + " is alive but did not re-open pipe '"
                    + pipeName + "' within " + MAX_WAIT_ATTEMPTS + " attempts");
        }
        return new PipeResolution.NotYet("pipe '" + pipeName
                + "' not listening yet; process " + pid + " is still alive");
    }

    /**
     * Exact, case-insensitive match only. {@code PipeClient.scanPipes} does a
     * prefix match, so a substring or near-miss must never be treated as ours.
     */
    private boolean isOurPipeVisible() {
        for (String candidate : pipeScan.get()) {
            if (candidate.equalsIgnoreCase(pipeName)) {
                return true;
            }
        }
        return false;
    }
}
