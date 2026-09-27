package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.clients.ClientRecord;
import com.botwithus.bot.cli.clients.ClientRegistry;
import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.cli.events.ConnectionHistory;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.cli.gui.usermode.board.ClientActions;
import com.botwithus.bot.cli.log.LogEntry;
import com.botwithus.bot.cli.output.AnsiCodes;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKey;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.core.rpc.RpcMetrics;
import com.botwithus.bot.core.runtime.LoadReport;
import com.botwithus.bot.core.runtime.ScriptLoadResult;
import com.botwithus.bot.core.runtime.ScriptProfiler;
import com.botwithus.bot.core.runtime.ScriptRunner;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.WeakHashMap;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The Dashboard over the live host: every client's runners, pooled RPC latency,
 * what needs attention, the dev loop, and the console, logs and events.
 *
 * <p>Render thread only. Each read walks the connections once; the RPC table,
 * the one expensive part, is reused for up to a second (see {@link RpcSpread}),
 * and stack traces are rendered once per crash.</p>
 */
public final class LiveDashboardModel implements DashboardModel {

    /** How many of the newest log lines the Logs tab looks through. */
    static final int LOG_LINES = 500;

    private static final double NANOS_PER_MS = 1_000_000.0;
    private static final double MS_PER_MINUTE = 60_000.0;
    private static final long SHORTEST_RATE_WINDOW_MS = 1_000L;

    private static final Comparator<RunnerRow> RUNNER_ORDER = Comparator
            .comparing(RunnerRow::status)
            .thenComparing(RunnerRow::clientLabel, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(RunnerRow::script, String.CASE_INSENSITIVE_ORDER);

    private static final Comparator<AttentionItem> ATTENTION_ORDER = Comparator
            .comparing(AttentionItem::severity)
            .thenComparing(item -> item.since().orElse(Instant.MIN), Comparator.reverseOrder());

    /** One runner, read once this frame. */
    private record Seen(ScriptRunner runner, RunnerRow row) { }

    private final CliContext ctx;
    private final CommandConsole console;
    private final String scriptsFolder;
    private final Consumer<ScriptRunner> settingsOpener;
    private final ClientActions clientActions;
    private final InstantSource clock;
    private final RpcSpread rpcSpread = new RpcSpread();
    private final DashboardActions actions = new Actions();
    private final Map<Throwable, String> traces = new WeakHashMap<>();
    private final Map<ScriptRunner, Boolean> settingsKnown = new WeakHashMap<>();
    private Instant since;
    private boolean isReset;
    private LoadReport seenReport;
    private Instant lastReload;
    private Future<?> reload;

    /**
     * @param scriptsFolder  the scripts folder as the sidebar shows it
     * @param settingsOpener opens the shared inspector on a runner
     * @param clientActions  the Clients board's actions, which the Dashboard's reconnect controls share
     */
    public LiveDashboardModel(CliContext ctx, CommandConsole console, String scriptsFolder,
                              Consumer<ScriptRunner> settingsOpener, ClientActions clientActions,
                              InstantSource clock) {
        this.ctx = ctx;
        this.console = console;
        this.scriptsFolder = scriptsFolder;
        this.settingsOpener = settingsOpener;
        this.clientActions = clientActions;
        this.clock = clock;
        this.since = clock.instant();
        this.seenReport = ctx.getLastLoadReport();
    }

    // ── Reads ───────────────────────────────────────────────────────────

    @Override
    public DashboardView view(Scope scope) {
        Instant now = clock.instant();
        noteLoadPass(now);
        List<Connection> all = ctx.getConnections();
        List<Connection> conns = all.stream().filter(c -> scope.includes(c.getName())).toList();
        List<Seen> seen = readRunners(conns);
        List<RunnerRow> rows = seen.stream().map(Seen::row).sorted(RUNNER_ORDER).toList();
        RpcSpread.Table rpc = rpcSpread.table(scope, metricsOf(conns), now);
        return new DashboardView(since, isReset, kpis(conns, rows, rpc, now), rows, rpc.rows(),
                attention(scope, conns, seen), devLoop(all), scopes(all, scope),
                setting(SettingKeys.COLLECT_RPC_TIMING), setting(SettingKeys.COLLECT_LOOP_TIMING));
    }

    @Override
    public LogsView logs(Scope scope, LogLevel level) {
        List<LogEntry> lines = new ArrayList<>();
        int errors = 0;
        for (LogEntry entry : ctx.getLogBuffer().tail(LOG_LINES)) {
            if (!scope.includes(entry.connection())) {
                continue;
            }
            if (LogLevel.ERROR.admits(entry.level())) {
                errors++;
            }
            if (level.admits(entry.level())) {
                lines.add(entry);
            }
        }
        return new LogsView(lines, errors);
    }

    /**
     * A client's events are its whole history, which follows its account across
     * pipes: the scope names its current pipe, and the history finds the client
     * that pipe belongs to now.
     */
    @Override
    public List<EventRow> events(Scope scope) {
        ConnectionHistory history = ctx.getConnectionHistory();
        List<HostEvent> events = scope.client().map(pipe -> history.forClient(pipe))
                .orElseGet(history::merged);
        Function<ClientRef, String> labels = eventLabels(ctx.getConnections());
        return events.stream().map(event -> EventRows.of(event, labels)).toList();
    }

    @Override
    public ConsoleView console() {
        List<ScopeOption> targets = ctx.getConnections().stream()
                .map(c -> new ScopeOption(Scope.of(c.getName()), labelOf(c)))
                .toList();
        return new ConsoleView(console.lines(), Optional.ofNullable(ctx.getActiveConnectionName()), targets,
                ctx.isMounted(), console.commandNames());
    }

    @Override
    public DashboardActions actions() {
        return actions;
    }

    // ── Runners ─────────────────────────────────────────────────────────

    private List<Seen> readRunners(List<Connection> conns) {
        List<Seen> seen = new ArrayList<>();
        for (Connection conn : conns) {
            String label = labelOf(conn);
            for (ScriptRunner runner : conn.getRuntime().getRunners()) {
                seen.add(new Seen(runner, row(conn.getName(), label, runner)));
            }
        }
        return seen;
    }

    private RunnerRow row(String pipe, String label, ScriptRunner runner) {
        ScriptProfiler p = runner.getProfiler();
        RunnerStatus status = RunnerFacts.status(runner);
        ScriptManifest manifest = runner.getManifest();
        String version = manifest != null ? manifest.version() : "";
        ScriptCategory category = manifest != null ? manifest.category() : ScriptCategory.UNCATEGORIZED;
        String script = runner.getScriptName();
        Optional<Instant> stalledSince = status == RunnerStatus.STALLED
                ? latest(pipe, e -> stalledAt(e, script)) : Optional.empty();
        return new RunnerRow(new RunnerRef(pipe, script), label, version, category, status,
                p.getLoopCount(), p.avgLoopMs(), ms(p.getLastLoopNanos()), ms(p.getMaxLoopNanos()),
                p.recentLoopNanos(), runner.health().totalCrashes(), stalledSince, hasSettings(runner));
    }

    private boolean hasSettings(ScriptRunner runner) {
        return settingsKnown.computeIfAbsent(runner, RunnerFacts::hasSettings);
    }

    // ── Needs attention ─────────────────────────────────────────────────

    private List<AttentionItem> attention(Scope scope, List<Connection> conns, List<Seen> seen) {
        List<AttentionItem> items = new ArrayList<>();
        for (Seen s : seen) {
            runnerItem(s).ifPresent(items::add);
        }
        for (Connection conn : conns) {
            connectionItem(conn).ifPresent(items::add);
        }
        if (scope.isAll()) {
            items.addAll(loadFailures());
        }
        items.sort(ATTENTION_ORDER);
        return items;
    }

    private Optional<AttentionItem> runnerItem(Seen s) {
        RunnerRow row = s.row();
        return switch (row.status()) {
            case CRASHED -> RunnerFacts.currentCrash(s.runner()).map(crash -> new AttentionItem.Crashed(
                    row.ref(), row.clientLabel(), crash, row.crashes(), trace(crash)));
            case STALLED -> Optional.of(new AttentionItem.Stalled(row.ref(), row.clientLabel(),
                    row.stalledSince(), row.loops()));
            case CUT_OFF -> Optional.of(new AttentionItem.CutOff(row.ref(), row.clientLabel(),
                    s.runner().liveness()));
            case RUNNING, STOPPED -> Optional.empty();
        };
    }

    private Optional<AttentionItem> connectionItem(Connection conn) {
        if (!isNotResponding(conn)) {
            return Optional.empty();
        }
        String pipe = conn.getName();
        Optional<Instant> lostAt = latest(pipe, LiveDashboardModel::lostAt);
        ReconnectState state = conn.currentReconnectState();
        return Optional.of(switch (state) {
            case ReconnectState.Reconnecting r -> new AttentionItem.NotResponding(
                    pipe, labelOf(conn), r.attempt(), r.nextDelayMs(), lostAt);
            case ReconnectState.GivingUp g -> new AttentionItem.GaveUp(pipe, labelOf(conn), g.attempts(), lostAt);
            case ReconnectState.Connected _, ReconnectState.Disconnected _ ->
                    new AttentionItem.NotResponding(pipe, labelOf(conn), 0, 0L, lostAt);
            case null -> new AttentionItem.NotResponding(pipe, labelOf(conn), 0, 0L, lostAt);
        });
    }

    /** The pipe is down, or the reconnect loop is retrying or has given up. */
    private static boolean isNotResponding(Connection conn) {
        ReconnectState state = conn.currentReconnectState();
        if (state == null) {
            return !conn.isAlive();
        }
        return switch (state) {
            case ReconnectState.Connected _ -> !conn.isAlive();
            case ReconnectState.Disconnected _, ReconnectState.Reconnecting _, ReconnectState.GivingUp _ -> true;
        };
    }

    private List<AttentionItem> loadFailures() {
        List<HostEvent> hostWide = ctx.getConnectionHistory().hostWide();
        List<AttentionItem> items = new ArrayList<>();
        for (ScriptLoadResult failure : ctx.getLastLoadReport().failures()) {
            Optional<Throwable> cause = failure.error();
            items.add(new AttentionItem.LoadFailed(failure.jar(),
                    cause.map(EventRows::oneLine).orElse("Unknown error"),
                    cause.map(this::trace).orElse(""),
                    latestIn(hostWide, e -> failedAt(e, failure.jar()))));
        }
        return items;
    }

    // ── Headline numbers and the dev loop ───────────────────────────────

    private Kpis kpis(List<Connection> conns, List<RunnerRow> rows, RpcSpread.Table rpc, Instant now) {
        int alive = 0;
        int reconnecting = 0;
        int worstAttempt = 0;
        for (Connection conn : conns) {
            alive += conn.isAlive() ? 1 : 0;
            OptionalInt attempt = attemptOf(conn.currentReconnectState());
            if (attempt.isPresent()) {
                reconnecting++;
                worstAttempt = Math.max(worstAttempt, attempt.getAsInt());
            }
        }
        double minutes = Math.max(Duration.between(since, now).toMillis(), SHORTEST_RATE_WINDOW_MS) / MS_PER_MINUTE;
        Optional<RunnerRow> slowest = rows.stream()
                .filter(r -> r.status() == RunnerStatus.RUNNING)
                .max(Comparator.comparingDouble(RunnerRow::avgMs));
        return new Kpis(alive, conns.size(), reconnecting, worstAttempt,
                count(rows, RunnerStatus.RUNNING), rows.size(), count(rows, RunnerStatus.STALLED),
                count(rows, RunnerStatus.CRASHED), count(rows, RunnerStatus.CUT_OFF),
                count(rows, RunnerStatus.STOPPED), rpc.calls(), rpc.errors(), rpc.calls() / minutes, slowest);
    }

    private DevLoopView devLoop(List<Connection> all) {
        LoadReport report = ctx.getLastLoadReport();
        int jarsLoaded = (int) report.results().stream()
                .filter(ScriptLoadResult::isSuccess).map(ScriptLoadResult::jar).distinct().count();
        int clients = (int) all.stream().filter(Connection::isAlive).count();
        Future<?> running = reload;
        return new DevLoopView(scriptsFolder, setting(SettingKeys.AUTO_RELOAD), ctx.isWatcherRunning(),
                setting(SettingKeys.RESTART_AFTER_RELOAD), Optional.ofNullable(lastReload),
                report.scripts().size(), jarsLoaded, report.failures().size(), clients,
                running != null && !running.isDone());
    }

    /** A load pass replaces the report, so a new report object means a pass has finished. */
    private void noteLoadPass(Instant now) {
        LoadReport current = ctx.getLastLoadReport();
        if (current != seenReport) {
            seenReport = current;
            lastReload = now;
        }
    }

    /** A switch's value; with no settings store (tests), the key's default. */
    private boolean setting(SettingKey<Boolean> key) {
        HostSettings settings = ctx.getSettings();
        return settings != null ? settings.get(key) : key.defaultValue();
    }

    private List<ScopeOption> scopes(List<Connection> all, Scope scope) {
        List<ScopeOption> out = new ArrayList<>();
        out.add(new ScopeOption(Scope.ALL, "All clients (" + all.size() + ")"));
        for (Connection conn : all) {
            String note = isNotResponding(conn) ? "not responding" : "";
            out.add(new ScopeOption(Scope.of(conn.getName()), labelOf(conn), note));
        }
        if (out.stream().noneMatch(o -> o.scope().equals(scope))) {
            out.add(new ScopeOption(scope, scope.client().orElse(""), "closed"));
        }
        return out;
    }

    // ── Small readers ───────────────────────────────────────────────────

    /** The client as the user knows it: the account's display name, else its name, else the pipe. */
    static String labelOf(Connection conn) {
        String account = conn.getAccountName();
        return conn.getDisplayName().orElse(account != null && !account.isBlank() ? account : conn.getName());
    }

    /**
     * An event's client as the rest of the Dashboard labels it while it has a
     * connection, else by the name the client registry has for it, else by its pipe.
     */
    private Function<ClientRef, String> eventLabels(List<Connection> conns) {
        ClientRegistry registry = ctx.getClientRegistry();
        return ref -> conns.stream().filter(c -> c.getName().equals(ref.pipe())).findFirst()
                .map(LiveDashboardModel::labelOf)
                .or(() -> registry.get(ref.key()).flatMap(ClientRecord::name))
                .orElse(ref.hasPipe() ? ref.pipe() : ref.key().value());
    }

    private static List<RpcMetrics> metricsOf(List<Connection> conns) {
        return conns.stream().map(c -> c.getRpc().getMetrics()).toList();
    }

    private static int count(List<RunnerRow> rows, RunnerStatus status) {
        return (int) rows.stream().filter(r -> r.status() == status).count();
    }

    private static double ms(long nanos) {
        return nanos / NANOS_PER_MS;
    }

    private static OptionalInt attemptOf(ReconnectState state) {
        if (state == null) {
            return OptionalInt.empty();
        }
        return switch (state) {
            case ReconnectState.Reconnecting r -> OptionalInt.of(r.attempt());
            case ReconnectState.Connected _, ReconnectState.Disconnected _, ReconnectState.GivingUp _ ->
                    OptionalInt.empty();
        };
    }

    private Optional<Instant> latest(String pipe, Function<HostEvent, Optional<Instant>> match) {
        return latestIn(ctx.getConnectionHistory().forClient(pipe), match);
    }

    private static Optional<Instant> latestIn(List<HostEvent> events, Function<HostEvent, Optional<Instant>> match) {
        for (int i = events.size() - 1; i >= 0; i--) {
            Optional<Instant> at = match.apply(events.get(i));
            if (at.isPresent()) {
                return at;
            }
        }
        return Optional.empty();
    }

    private static Optional<Instant> stalledAt(HostEvent event, String script) {
        return switch (event) {
            case HostEvent.ScriptStalled s when s.script().equals(script) -> Optional.of(s.at());
            default -> Optional.empty();
        };
    }

    private static Optional<Instant> lostAt(HostEvent event) {
        return switch (event) {
            case HostEvent.ConnectionLost lost -> Optional.of(lost.at());
            default -> Optional.empty();
        };
    }

    private static Optional<Instant> failedAt(HostEvent event, Path jar) {
        return switch (event) {
            case HostEvent.ScriptLoadFailed f when f.jar().equals(jar) -> Optional.of(f.at());
            default -> Optional.empty();
        };
    }

    private String trace(LastCrash crash) {
        return crash.cause() != null ? trace(crash.cause()) : "";
    }

    private String trace(Throwable cause) {
        return traces.computeIfAbsent(cause, t -> {
            StringWriter out = new StringWriter();
            t.printStackTrace(new PrintWriter(out));
            return out.toString();
        });
    }

    private Optional<ScriptRunner> runner(RunnerRef ref) {
        return ctx.getConnections().stream()
                .filter(c -> c.getName().equals(ref.client()))
                .findFirst()
                .map(c -> c.getRuntime().findRunner(ref.script()));
    }

    private void resetMetrics() {
        for (Connection conn : ctx.getConnections()) {
            conn.getRpc().getMetrics().reset();
            conn.getRuntime().getRunners().forEach(r -> r.getProfiler().reset());
        }
        since = clock.instant();
        isReset = true;
        rpcSpread.invalidate();
    }

    // ── Actions ─────────────────────────────────────────────────────────

    private final class Actions implements DashboardActions {

        @Override
        public void stop(RunnerRef ref) {
            runner(ref).ifPresent(ScriptRunner::stop);
        }

        @Override
        public void run(RunnerRef ref) {
            runner(ref).ifPresent(ScriptRunner::start);
        }

        @Override
        public void openSettings(RunnerRef ref) {
            runner(ref).ifPresent(settingsOpener);
        }

        @Override
        public void threadDump(RunnerRef ref) {
            runner(ref).ifPresent(r -> ThreadDump.print(r, ref, console.out()));
        }

        @Override
        public void retryNow(String pipe) {
            clientActions.retryNow(ctx.clientKeyOf(pipe));
        }

        @Override
        public void reload() {
            Future<?> running = reload;
            if (running == null || running.isDone()) {
                reload = console.submit("reload");
            }
        }

        @Override
        public void setWatch(boolean isOn) {
            set(SettingKeys.AUTO_RELOAD, isOn);
        }

        @Override
        public void setRestartAfterReload(boolean isOn) {
            set(SettingKeys.RESTART_AFTER_RELOAD, isOn);
        }

        @Override
        public void resetMetrics() {
            LiveDashboardModel.this.resetMetrics();
        }

        @Override
        public void submit(String line) {
            console.submit(line);
        }

        @Override
        public void note(String line) {
            console.out().println(AnsiCodes.dim(line));
        }

        @Override
        public void setConsoleTarget(String pipe) {
            ctx.setActive(pipe);
        }

        @Override
        public void clearLogs() {
            ctx.getLogBuffer().clear();
        }

        private void set(SettingKey<Boolean> key, boolean isOn) {
            HostSettings settings = ctx.getSettings();
            if (settings != null) {
                settings.set(key, isOn);
            }
        }
    }
}
