package com.botwithus.bot.core.runlog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Every script run's log, for the whole host process: opens a run's file under
 * {@code ~/.botwithus/logs/scripts/<slug>/}, knows which run the calling thread
 * belongs to, and remembers each script's latest log and crash for a report.
 *
 * <p>One instance, made at the composition root and passed to each connection's
 * {@code ScriptRuntime}; not a singleton. The thread tag is an
 * {@link InheritableThreadLocal} held by this instance (the same shape as
 * {@code ScriptGate}), so a thread a script spawns logs into the script's run.</p>
 *
 * <h2>API for a report</h2>
 * <ul>
 *   <li>{@link #currentOrLastLog(String, String)}: the open run's file, else the
 *   last one's, for a connection and script name.</li>
 *   <li>{@link #lastCrash(String, String)}: the last crash's {@link CrashSummary},
 *   already redacted.</li>
 * </ul>
 */
public final class RunLogs {

    /** Spec location, relative to the user's home directory. */
    public static final List<String> DEFAULT_ROOT = List.of(".botwithus", "logs", "scripts");
    private static final Logger log = LoggerFactory.getLogger(RunLogs.class);
    private static final int RUN_ID_BYTES = 16;
    private static final int RUN_ID_PREFIX = 8;
    private static final String THREAD_CRUMB = "thread";

    private final Path root;
    private final HostIdentity host;
    private final Clock clock;
    private final RunLogRetention retention;
    private final SecureRandom random = new SecureRandom();
    private final InheritableThreadLocal<ScriptRun> current = new InheritableThreadLocal<>();
    private final Map<RunKey, ScriptRun> latestRuns = new ConcurrentHashMap<>();
    private final Map<RunKey, Path> latestFiles = new ConcurrentHashMap<>();
    private final Map<RunKey, CrashSummary> crashes = new ConcurrentHashMap<>();
    private final Set<Path> openFiles = ConcurrentHashMap.newKeySet();
    /** Set while logback dispatches an event on this thread; see {@link RunLogLogback}. */
    private final ThreadLocal<Boolean> inLogEvent = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Writes run logs under {@code root}. */
    public RunLogs(Path root, HostIdentity host, Clock clock) {
        this(root, host, clock, new RunLogRetention());
    }

    RunLogs(Path root, HostIdentity host, Clock clock, RunLogRetention retention) {
        this.root = root;
        this.host = host;
        this.clock = clock;
        this.retention = retention;
    }

    /** Writes run logs to the spec location under the user's home directory. */
    public static RunLogs atDefaultRoot(HostIdentity host, Clock clock) {
        Path home = Path.of(System.getProperty("user.home"));
        return new RunLogs(home.resolve(String.join("/", DEFAULT_ROOT)), host, clock);
    }

    /** Keeps breadcrumbs and crash summaries but writes no file; test seams and wiring without a disk. */
    public static RunLogs withoutFiles(HostIdentity host, Clock clock) {
        return new RunLogs(null, host, clock);
    }

    /**
     * Starts a run: enforces retention, creates the file and writes its header.
     * Never throws for a disk problem; the run then has no file but still
     * collects breadcrumbs and a crash summary.
     */
    public ScriptRun open(RunRequest request) {
        Instant startedAt = clock.instant();
        String runId = newRunId();
        RunKey key = new RunKey(request.connectionName(), request.script().name());
        Redactor redactor = new Redactor(request.names());
        RunLogFile file = createFile(request, runId, startedAt, redactor);
        ScriptRun run = new ScriptRun(new ScriptRun.RunParts(runId, key, file, redactor, clock,
                request.isScriptFrame(), summary -> crashes.put(key, summary), this::closed));
        latestRuns.put(key, run);
        run.logFile().ifPresent(p -> latestFiles.put(key, p));
        return run;
    }

    /** Tags the calling thread, and threads it later spawns, as belonging to {@code run}. */
    public void enter(ScriptRun run) {
        current.set(run);
    }

    /** Clears the calling thread's tag. */
    public void exit() {
        current.remove();
    }

    /** The run the calling thread belongs to. */
    public Optional<ScriptRun> current() {
        return Optional.ofNullable(current.get());
    }

    /**
     * A line a thread printed to {@code System.out} or {@code System.err}, written
     * into that thread's run as a logger named {@code stream}. Dropped when the
     * thread belongs to no run, and when the line is logback's own console echo
     * of an event the run already has.
     */
    public void stdLine(String stream, String level, String line) {
        if (inLogEvent.get()) {
            return;
        }
        current().ifPresent(run -> run.log(clock.instant(), level, Thread.currentThread().getName(),
                stream, line, List.of()));
    }

    /**
     * Records an RPC call as a breadcrumb of the calling thread's run. Host
     * threads belong to no run and record nothing. Bound to
     * {@code RpcClient.setCallObserver} at the connection setup site.
     */
    public void recordRpc(String method, Map<String, Object> params) {
        current().filter(ScriptRun::isOpen)
                .ifPresent(run -> run.crumb(RpcCrumb.KIND, RpcCrumb.describe(method, params)));
    }

    /**
     * A default uncaught-exception handler for the JVM. It logs the full trace on
     * the dying thread, so a thread a script spawned has it written into that
     * script's run log, and records a {@code thread} breadcrumb. It then hands
     * over to {@code next} when there is one; when there is none it does not
     * also print the trace, which would be a second copy.
     */
    public Thread.UncaughtExceptionHandler uncaughtHandler(Thread.UncaughtExceptionHandler next) {
        return (thread, error) -> {
            current().ifPresent(run -> run.crumb(THREAD_CRUMB,
                    thread.getName() + " died: " + error));
            log.error("Uncaught exception on thread {}", thread.getName(), error);
            if (next != null) {
                next.uncaughtException(thread, error);
            }
        };
    }

    void beginLogEvent() {
        inLogEvent.set(Boolean.TRUE);
    }

    void endLogEvent() {
        inLogEvent.set(Boolean.FALSE);
    }

    /** The open run's log file for this script on this connection, else the last run's. */
    public Optional<Path> currentOrLastLog(String connectionName, String scriptName) {
        return Optional.ofNullable(latestFiles.get(new RunKey(connectionName, scriptName)));
    }

    /** The last crash of this script on this connection, in any run of this process. */
    public Optional<CrashSummary> lastCrash(String connectionName, String scriptName) {
        return Optional.ofNullable(crashes.get(new RunKey(connectionName, scriptName)));
    }

    /** The run currently open for this script on this connection, if any. */
    public Optional<ScriptRun> openRun(String connectionName, String scriptName) {
        return Optional.ofNullable(latestRuns.get(new RunKey(connectionName, scriptName)));
    }

    private RunLogFile createFile(RunRequest request, String runId, Instant startedAt,
                                  Redactor redactor) {
        if (root == null) {
            return null;
        }
        Path scriptDir = root.resolve(ScriptSlug.of(request.script().name()));
        Path path = scriptDir.resolve(RunLogClock.fileStamp(startedAt) + "-"
                + runId.substring(0, RUN_ID_PREFIX) + RunLogRetention.EXTENSION);
        try {
            retention.beforeNewFile(root, scriptDir, Set.copyOf(openFiles));
            RunLogFile file = RunLogFile.create(path, redactor);
            openFiles.add(path.toAbsolutePath().normalize());
            file.writeRedacted(header(request, runId, startedAt).render(redactor));
            return file;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not create a run log for {}: {}", request.script().name(), e.toString());
            return null;
        }
    }

    private RunLogHeader header(RunRequest request, String runId, Instant startedAt) {
        ScriptIdentity script = request.script();
        AgentIdentity agent = request.agent();
        return new RunLogHeader(runId, host.hostVersion(), host.protocolVersion(),
                agent.agentBuild(), agent.gameRevision(), script.name(), script.version(),
                script.author(), script.source(), script.sha256(), host.os(), host.runtime(),
                startedAt, request.slot());
    }

    private void closed(ScriptRun run) {
        run.logFile().ifPresent(p -> openFiles.remove(p.toAbsolutePath().normalize()));
        latestRuns.remove(run.key(), run);
    }

    private String newRunId() {
        byte[] bytes = new byte[RUN_ID_BYTES];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * What a run is opened with.
     *
     * @param script         the script's identity (manifest, source, hash)
     * @param connectionName the connection's name; only ever written redacted
     * @param slot           the connection's integer slot, written in place of its name
     * @param agent          what the agent reports about itself
     * @param names          live view of the names to redact for this run
     * @param isScriptFrame  which stack frames are the script's own, for {@code top_frame}
     */
    public record RunRequest(ScriptIdentity script, String connectionName, int slot,
                             AgentIdentity agent, Supplier<KnownNames> names,
                             Predicate<StackTraceElement> isScriptFrame) {
        public RunRequest {
            agent = agent != null ? agent : AgentIdentity.UNKNOWN;
            names = names != null ? names : () -> KnownNames.NONE;
            isScriptFrame = isScriptFrame != null ? isScriptFrame : f -> false;
        }
    }
}
