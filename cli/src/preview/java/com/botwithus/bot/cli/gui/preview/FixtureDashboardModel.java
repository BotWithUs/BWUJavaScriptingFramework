package com.botwithus.bot.cli.gui.preview;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.cli.gui.AnsiOutputBuffer;
import com.botwithus.bot.cli.gui.pages.dashboard.AttentionItem;
import com.botwithus.bot.cli.gui.pages.dashboard.ConsoleView;
import com.botwithus.bot.cli.gui.pages.dashboard.DashboardActions;
import com.botwithus.bot.cli.gui.pages.dashboard.DashboardModel;
import com.botwithus.bot.cli.gui.pages.dashboard.DashboardView;
import com.botwithus.bot.cli.gui.pages.dashboard.DevLoopView;
import com.botwithus.bot.cli.gui.pages.dashboard.EventRow;
import com.botwithus.bot.cli.gui.pages.dashboard.Kpis;
import com.botwithus.bot.cli.gui.pages.dashboard.LogLevel;
import com.botwithus.bot.cli.gui.pages.dashboard.LogsView;
import com.botwithus.bot.cli.gui.pages.dashboard.RpcRow;
import com.botwithus.bot.cli.gui.pages.dashboard.RunnerRef;
import com.botwithus.bot.cli.gui.pages.dashboard.RunnerRow;
import com.botwithus.bot.cli.gui.pages.dashboard.Scope;
import com.botwithus.bot.cli.gui.pages.dashboard.ScopeOption;
import com.botwithus.bot.cli.gui.runners.RunnerStatus;
import com.botwithus.bot.cli.log.LogEntry;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * DEV ONLY. Canned Dashboard views for the preview: the prototype's busy fleet
 * (a stalled and a crashed runner, a JAR that failed to load, a client not
 * responding), a quiet one where nothing needs a look, and an empty host.
 * Account names, timings and traces are made-up sample data.
 */
final class FixtureDashboardModel implements DashboardModel {

    /** Which host the fixture pretends to be. */
    enum Fleet { BUSY, QUIET, EMPTY }

    private static final long NANOS_PER_MS = 1_000_000L;
    private static final double GOLDEN = 0.618_033_988;
    private static final double JITTER_LOW = 0.75;
    private static final double JITTER_SPAN = 0.5;
    private static final double SPIKE = 2.8;
    private static final int SPIKE_SLOT = 20;
    private static final int LANE = 24;
    private static final Duration SINCE_RESET = Duration.ofMinutes(6).plusSeconds(12);
    private static final double SCOPED_SHARE = 0.16;

    private record Client(String pipe, String name, boolean isAlive) { }

    private static final Client OAKHEART = new Client("BotWithUs_14208", "Oakheart", true);
    private static final Client FERNMOSS = new Client("BotWithUs_9932", "Fernmoss", true);
    private static final Client QUILLON = new Client("BotWithUs_11820", "Quillon", true);
    private static final Client HOLLOWMERE = new Client("BotWithUs_10344", "Hollowmere", false);
    private static final Client TAMSIN = new Client("BotWithUs_15002", "Tamsin Vale", true);
    private static final Client KESTREL = new Client("BotWithUs_18104", "Kestrel Moor", true);
    private static final Client ASHGROVE = new Client("BotWithUs_17012", "Ashgrove", true);

    private final Instant now = Instant.now();
    private final AnsiOutputBuffer console = new AnsiOutputBuffer();
    private final DashboardActions actions = new NoActions();
    private Fleet fleet;

    FixtureDashboardModel(Fleet fleet) {
        show(fleet);
    }

    /** Pretends to be {@code next} from the next frame on. */
    void show(Fleet next) {
        this.fleet = next;
        console.clear();
        FixtureConsole.fill(console, next != Fleet.EMPTY);
    }

    private List<Client> clients() {
        return switch (fleet) {
            case BUSY -> List.of(OAKHEART, FERNMOSS, QUILLON, HOLLOWMERE, TAMSIN, KESTREL, ASHGROVE);
            case QUIET -> List.of(OAKHEART, QUILLON, KESTREL);
            case EMPTY -> List.of();
        };
    }

    @Override
    public DashboardView view(Scope scope) {
        List<RunnerRow> runners = runners().stream().filter(r -> scope.includes(r.ref().client())).toList();
        List<AttentionItem> attention = attention().stream()
                .filter(a -> a.client().map(scope::includes).orElse(scope.isAll())).toList();
        List<RpcRow> rpc = scope.isAll() ? rpc() : scaled(rpc());
        return new DashboardView(now.minus(SINCE_RESET), true, kpis(scope, runners, rpc), runners, rpc,
                attention, devLoop(), scopes(), true, true);
    }

    @Override
    public LogsView logs(Scope scope, LogLevel level) {
        List<LogEntry> all = fleet == Fleet.EMPTY ? List.of() : FixtureConsole.logs(now);
        List<LogEntry> lines = new ArrayList<>();
        int errors = 0;
        for (LogEntry e : all) {
            if (scope.includes(e.connection())) {
                errors += "ERROR".equals(e.level()) ? 1 : 0;
                if (level.admits(e.level())) {
                    lines.add(e);
                }
            }
        }
        return new LogsView(lines, errors);
    }

    @Override
    public List<EventRow> events(Scope scope) {
        if (fleet == Fleet.EMPTY) {
            return List.of();
        }
        return FixtureConsole.events(now).stream()
                .filter(e -> scope.isAll() || e.clientLabel().equals(nameOf(scope)))
                .toList();
    }

    @Override
    public ConsoleView console() {
        List<ScopeOption> targets = clients().stream()
                .map(c -> new ScopeOption(Scope.of(c.pipe()), c.name())).toList();
        Optional<String> target = targets.isEmpty() ? Optional.empty() : Optional.of(OAKHEART.pipe());
        return new ConsoleView(console.snapshot(), target, targets, false, List.of("help", "reload", "scripts"));
    }

    @Override
    public DashboardActions actions() {
        return actions;
    }

    private String nameOf(Scope scope) {
        return clients().stream().filter(c -> scope.includes(c.pipe())).findFirst().map(Client::name).orElse("");
    }

    // ── Runners ─────────────────────────────────────────────────────────

    private List<RunnerRow> runners() {
        return switch (fleet) {
            case EMPTY -> List.of();
            case QUIET -> List.of(
                    row(OAKHEART, "Woodcutting", "2.0", ScriptCategory.WOODCUTTING, RunnerStatus.RUNNING,
                            1_742, 142, 139, 488, 0, false),
                    row(KESTREL, "Walk to Flag", "1.0", ScriptCategory.UTILITY, RunnerStatus.RUNNING,
                            3_906, 74, 70, 301, 0, false),
                    row(QUILLON, "Example Script", "1.0", ScriptCategory.UTILITY, RunnerStatus.STOPPED,
                            0, 0, 0, 0, 0, false));
            case BUSY -> busyRunners();
        };
    }

    private List<RunnerRow> busyRunners() {
        return List.of(
                row(TAMSIN, "Cook's Assistant", "1.0", ScriptCategory.QUESTING, RunnerStatus.CRASHED,
                        1_204, 188, 0, 912, 1, false),
                row(FERNMOSS, "Divination", "1.0", ScriptCategory.DIVINATION, RunnerStatus.STALLED,
                        4_410, 96, 38_020, 38_020, 0, false),
                row(ASHGROVE, "The Restless Ghost", "0.1", ScriptCategory.QUESTING, RunnerStatus.RUNNING,
                        612, 211, 204, 1_480, 0, true),
                row(KESTREL, "Walk to Flag", "1.0", ScriptCategory.UTILITY, RunnerStatus.RUNNING,
                        3_906, 74, 70, 301, 0, false),
                row(OAKHEART, "Woodcutting", "2.0", ScriptCategory.WOODCUTTING, RunnerStatus.RUNNING,
                        1_742, 142, 139, 488, 0, false),
                row(QUILLON, "Example Script", "1.0", ScriptCategory.UTILITY, RunnerStatus.STOPPED,
                        0, 0, 0, 0, 0, false),
                row(KESTREL, "Location Probe", "1.0", ScriptCategory.UTILITY, RunnerStatus.STOPPED,
                        88, 12, 11, 31, 0, false));
    }

    private RunnerRow row(Client c, String script, String version, ScriptCategory category, RunnerStatus status,
                          long loops, double avg, double last, double max, long crashes, boolean hasSpike) {
        boolean isLooping = status == RunnerStatus.RUNNING || status == RunnerStatus.STALLED;
        long[] lane = isLooping ? lane(avg, hasSpike) : new long[0];
        Optional<Instant> stalled = status == RunnerStatus.STALLED
                ? Optional.of(now.minusSeconds(38)) : Optional.empty();
        return new RunnerRow(new RunnerRef(c.pipe(), script), c.name(), version, category, status, loops, avg,
                last, max, lane, crashes, stalled, "Woodcutting".equals(script) || "Example Script".equals(script));
    }

    /** 24 loops around {@code avgMs}, deterministic so every render of the preview matches. */
    private static long[] lane(double avgMs, boolean hasSpike) {
        long[] lane = new long[LANE];
        for (int i = 0; i < LANE; i++) {
            double frac = (i * GOLDEN) % 1.0;
            lane[i] = (long) (avgMs * (JITTER_LOW + JITTER_SPAN * frac) * NANOS_PER_MS);
        }
        if (hasSpike) {
            lane[SPIKE_SLOT] = (long) (avgMs * SPIKE * NANOS_PER_MS);
        }
        return lane;
    }

    // ── RPC, attention, numbers ─────────────────────────────────────────

    private List<RpcRow> rpc() {
        if (fleet == Fleet.EMPTY) {
            return List.of();
        }
        return List.of(
                new RpcRow("query_entities", 18_420, 0, 3.1, 2.4, 7.8, 14.2),
                new RpcRow("get_varp", 12_960, 0, 0.9, 0.7, 1.9, 3.4),
                new RpcRow("query_inventories", 6_120, 0, 2.2, 1.8, 4.9, 9.0),
                new RpcRow("get_local_player", 5_874, 0, 1.1, 0.9, 2.2, 4.1),
                new RpcRow("walk_status", 3_210, 0, 1.4, 1.1, 3.0, 6.6),
                new RpcRow("get_component", 2_980, 1, 1.8, 1.3, 4.4, 11.9),
                new RpcRow("queue_action", 1_466, 3, 4.6, 3.2, 12.8, 58.3),
                new RpcRow("find_world_path", 402, 1, 38.4, 21.7, 96.2, 188.0),
                new RpcRow("get_interface_tree", 118, 0, 22.6, 18.9, 51.4, 77.0),
                new RpcRow("walk_world_path", 96, 0, 6.2, 4.8, 13.1, 20.4),
                new RpcRow("get_account_info", 14, 0, 2.0, 1.7, 3.6, 3.9),
                new RpcRow("take_screenshot", 3, 0, 142.0, 131.0, 176.0, 176.0));
    }

    private static List<RpcRow> scaled(List<RpcRow> rows) {
        return rows.stream().map(r -> new RpcRow(r.method(), Math.round(r.calls() * SCOPED_SHARE),
                Math.round(r.errors() * SCOPED_SHARE), r.avgMs(), r.p50Ms(), r.p95Ms(), r.p99Ms())).toList();
    }

    private List<AttentionItem> attention() {
        if (fleet != Fleet.BUSY) {
            return List.of();
        }
        LastCrash crash = new LastCrash(Phase.ON_LOOP, 1_204, now.minusSeconds(233), FixtureConsole.sampleNpe());
        return List.of(
                new AttentionItem.LoadFailed(Path.of("scripts", "woodcutting-1.0-SNAPSHOT.jar"),
                        "ServiceConfigurationError: provider not found", FixtureConsole.LOAD_TRACE,
                        Optional.of(now.minusSeconds(128))),
                new AttentionItem.Crashed(new RunnerRef(TAMSIN.pipe(), "Cook's Assistant"), TAMSIN.name(), crash, 1,
                        FixtureConsole.CRASH_TRACE),
                new AttentionItem.NotResponding(HOLLOWMERE.pipe(), HOLLOWMERE.name(), 4, 8_000L,
                        Optional.of(now.minusSeconds(42))),
                new AttentionItem.Stalled(new RunnerRef(FERNMOSS.pipe(), "Divination"), FERNMOSS.name(),
                        Optional.of(now.minusSeconds(38)), 4_410));
    }

    private Kpis kpis(Scope scope, List<RunnerRow> runners, List<RpcRow> rpc) {
        List<Client> inScope = clients().stream().filter(c -> scope.includes(c.pipe())).toList();
        int alive = (int) inScope.stream().filter(Client::isAlive).count();
        int reconnecting = inScope.size() - alive;
        long calls = rpc.stream().mapToLong(RpcRow::calls).sum();
        long errors = rpc.stream().mapToLong(RpcRow::errors).sum();
        double perMin = calls / (SINCE_RESET.toMillis() / (double) Duration.ofMinutes(1).toMillis());
        Optional<RunnerRow> slowest = runners.stream().filter(r -> r.status() == RunnerStatus.RUNNING)
                .max((a, b) -> Double.compare(a.avgMs(), b.avgMs()));
        return new Kpis(alive, inScope.size(), reconnecting, reconnecting > 0 ? 4 : 0,
                count(runners, RunnerStatus.RUNNING), runners.size(), count(runners, RunnerStatus.STALLED),
                count(runners, RunnerStatus.CRASHED), count(runners, RunnerStatus.CUT_OFF),
                count(runners, RunnerStatus.STOPPED), calls, errors, perMin, slowest);
    }

    private static int count(List<RunnerRow> rows, RunnerStatus status) {
        return (int) rows.stream().filter(r -> r.status() == status).count();
    }

    private DevLoopView devLoop() {
        return switch (fleet) {
            case BUSY -> new DevLoopView("scripts/", true, true, false, Optional.of(now.minusSeconds(128)),
                    9, 8, 1, 6, false);
            case QUIET -> new DevLoopView("scripts/", true, true, true, Optional.of(now.minusSeconds(900)),
                    10, 9, 0, 3, false);
            case EMPTY -> new DevLoopView("scripts/", false, false, false, Optional.empty(), 0, 0, 0, 0, false);
        };
    }

    private List<ScopeOption> scopes() {
        List<ScopeOption> out = new ArrayList<>();
        out.add(new ScopeOption(Scope.ALL, "All clients (" + clients().size() + ")"));
        for (Client c : clients()) {
            out.add(new ScopeOption(Scope.of(c.pipe()), c.name(), c.isAlive() ? "" : "not responding"));
        }
        return out;
    }

    /** The preview never acts on anything; a click only needs to not throw. */
    private static final class NoActions implements DashboardActions {
        @Override public void stop(RunnerRef runner) { }
        @Override public void run(RunnerRef runner) { }
        @Override public void openSettings(RunnerRef runner) { }
        @Override public void threadDump(RunnerRef runner) { }
        @Override public void retryNow(String pipe) { }
        @Override public void reload() { }
        @Override public void setWatch(boolean isOn) { }
        @Override public void setRestartAfterReload(boolean isOn) { }
        @Override public void resetMetrics() { }
        @Override public void submit(String line) { }
        @Override public void note(String line) { }
        @Override public void setConsoleTarget(String pipe) { }
        @Override public void clearLogs() { }
    }

}
