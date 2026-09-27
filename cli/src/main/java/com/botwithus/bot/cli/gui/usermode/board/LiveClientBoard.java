package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.ReconnectController;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.sdn.SdnCatalogueRefresher;
import com.botwithus.bot.core.sdn.SdnInstaller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * {@link ClientBoard} over the host's real connections.
 *
 * <p>Two pieces of card state have no home in the runtime and are kept here, on
 * the UI thread: when a client was first seen dead (for "No reply for 0:42"), and
 * which reconnects the user cancelled. Cancelling closes the controller, but its
 * last published state stays {@code Reconnecting}; without the second set the card
 * would claim to be retrying forever.</p>
 *
 * <p>Nothing here blocks the render thread. Reconnecting and loading the script
 * catalogue both do pipe or disk work, so they run on the host's command executor;
 * {@link #catalog()} answers from the last completed load.</p>
 */
public final class LiveClientBoard implements ClientBoard {

    private static final Logger log = LoggerFactory.getLogger(LiveClientBoard.class);

    private final CliContext ctx;
    private final Consumer<String> logOpener;
    private final Clock clock;
    private final Actions actions = new Actions();

    private final Map<String, Long> deadSinceMillis = new HashMap<>();
    private final Set<String> cancelledReconnects = new HashSet<>();
    private final LiveSubscriptions subscriptions;
    private final Executor commandExecutor;
    private final AtomicBoolean catalogLoadQueued = new AtomicBoolean();
    /** Written by the command executor, read by the render thread. */
    private volatile Catalog loadedCatalog = Catalog.EMPTY;

    /** One load of the installed scripts; the entry keys index {@code scripts}. */
    private record Catalog(List<BotScript> scripts, List<ScriptEntry> entries, List<LocalScript> locals) {
        static final Catalog EMPTY = new Catalog(List.of(), List.of(), List.of());
    }

    /**
     * Queues the first catalogue load, so the picker has scripts to offer by the
     * time it is first opened.
     *
     * @param logOpener       shows the log for a client; receives the client id
     * @param catalogue       the SDN catalogue refresher the Scripts Store panel also reads
     * @param installer       installs a subscribed script through the launcher
     * @param commandExecutor runs the blocking work (reconnects, catalogue loads) off
     *                        the render thread; the one console commands run on
     */
    public LiveClientBoard(CliContext ctx, Consumer<String> logOpener, Clock clock,
                           SdnCatalogueRefresher catalogue, SdnInstaller installer,
                           Executor commandExecutor) {
        this.ctx = ctx;
        this.logOpener = logOpener;
        this.clock = clock;
        this.commandExecutor = commandExecutor;
        this.subscriptions = new LiveSubscriptions(ctx::getConnections, catalogue, installer::install,
                installer.ledger()::find, () -> loadedCatalog.locals(),
                task -> Thread.ofVirtual().name("sdn-subscription-install").start(task));
        requestCatalogLoad();
    }

    @Override
    public List<ClientView> clients() {
        List<ClientView> views = new ArrayList<>();
        for (Connection conn : ctx.getConnections()) {
            views.add(new ClientView(conn.getName(), displayName(conn), worldOf(conn), statusOf(conn)));
        }
        deadSinceMillis.keySet().retainAll(views.stream().map(ClientView::id).toList());
        return views;
    }

    @Override
    public BoardStatus status() {
        List<Connection> conns = ctx.getConnections();
        boolean allGaveUp = !conns.isEmpty() && conns.stream().allMatch(this::hasGivenUp);
        int attempts = conns.stream()
                .map(Connection::currentReconnectState)
                .mapToInt(LiveClientBoard::attemptsIfGaveUp)
                .max().orElse(0);
        var settings = ctx.getSettings();
        return new BoardStatus(
                allGaveUp,
                attempts,
                ctx.getActiveConnectionName(),
                settings != null && settings.get(SettingKeys.AUTO_CONNECT),
                "\\\\.\\pipe\\" + PipeClient.NAME_PREFIX + "*",
                ctx.isMounted() ? ctx.getMountedConnectionName() : null,
                ctx.isWatcherRunning());
    }

    /**
     * The scripts from the last completed load, which may be empty until the first
     * load finishes. Also queues a fresh load, so JARs dropped in since show up the
     * next time the picker opens. Never touches the disk on the calling thread.
     */
    @Override
    public List<ScriptEntry> catalog() {
        requestCatalogLoad();
        return loadedCatalog.entries();
    }

    /** Queues one catalogue load on the command executor; a load already queued absorbs the request. */
    private void requestCatalogLoad() {
        if (catalogLoadQueued.compareAndSet(false, true)) {
            commandExecutor.execute(this::loadCatalog);
        }
    }

    private void loadCatalog() {
        catalogLoadQueued.set(false);
        try {
            List<BotScript> all = new ArrayList<>(ctx.loadScripts());
            all.addAll(ctx.loadBlueprints());
            loadedCatalog = catalogOf(List.copyOf(all));
        } catch (RuntimeException e) {
            log.warn("Loading the script catalogue failed; keeping the previous one: {}", e.toString());
        }
    }

    private static Catalog catalogOf(List<BotScript> scripts) {
        List<ScriptEntry> entries = new ArrayList<>();
        List<LocalScript> locals = new ArrayList<>();
        for (int i = 0; i < scripts.size(); i++) {
            ScriptInfo info = infoOf(scripts.get(i));
            entries.add(new ScriptEntry(i, info));
            locals.add(new LocalScript(i, scripts.get(i), info.name()));
        }
        return new Catalog(scripts, List.copyOf(entries), List.copyOf(locals));
    }

    /**
     * The script behind a picker row. A reload can land while the picker is open,
     * so the row's key is trusted only while it still names the same script; if
     * the list shifted, the script is found by name instead.
     */
    private Optional<BotScript> scriptFor(ScriptEntry entry) {
        Catalog current = loadedCatalog;
        int key = entry.key();
        if (key >= 0 && key < current.scripts().size()
                && current.entries().get(key).info().name().equals(entry.info().name())) {
            return Optional.of(current.scripts().get(key));
        }
        return current.locals().stream()
                .filter(local -> local.name().equals(entry.info().name()))
                .map(LocalScript::script)
                .findFirst();
    }

    @Override
    public SubscriptionGroup subscriptions(String clientId) {
        return subscriptions.group(clientId);
    }

    @Override
    public ClientActions actions() {
        return actions;
    }

    // ── Status mapping ─────────────────────────────────────────────────────

    private ClientStatus statusOf(Connection conn) {
        boolean cancelled = cancelledReconnects.contains(conn.getName());
        Optional<ClientStatus> retrying = cancelled ? Optional.empty() : retryingStatus(conn);
        if (retrying.isPresent()) {
            return retrying.get();
        }
        if (!conn.isAlive()) {
            long since = deadSinceMillis.computeIfAbsent(conn.getName(), k -> clock.millis());
            return new ClientStatus.Lost(clock.millis() - since, lastScriptInfo(conn));
        }
        deadSinceMillis.remove(conn.getName());
        cancelledReconnects.remove(conn.getName());
        return aliveStatus(conn);
    }

    private static Optional<ClientStatus> retryingStatus(Connection conn) {
        ReconnectState state = conn.currentReconnectState();
        if (state == null) {
            return Optional.empty();
        }
        return switch (state) {
            case ReconnectState.Reconnecting r -> {
                ReconnectController controller = conn.getReconnectController();
                int max = controller != null ? controller.maxAttempts() : r.attempt();
                yield Optional.of(new ClientStatus.Reconnecting(r.attempt(), max, r.nextDelayMs()));
            }
            case ReconnectState.Connected ignored -> Optional.empty();
            case ReconnectState.Disconnected ignored -> Optional.empty();
            case ReconnectState.GivingUp ignored -> Optional.empty();
        };
    }

    private ClientStatus aliveStatus(Connection conn) {
        List<ScriptRunner> runners = conn.getRuntime().getRunners();
        Optional<ScriptRunner> running = runners.stream().filter(ScriptRunner::isRunning).findFirst();
        if (running.isPresent()) {
            ScriptRunner r = running.get();
            return new ClientStatus.Running(infoOf(r), r.getProfiler().avgLoopMs(),
                    r.getProfiler().recentLoopNanos());
        }
        Optional<ClientStatus> crashed = latestCrash(runners);
        if (crashed.isPresent()) {
            return crashed.get();
        }
        return conn.getAccountName() == null ? new ClientStatus.Loading() : new ClientStatus.Idle();
    }

    private static Optional<ClientStatus> latestCrash(List<ScriptRunner> runners) {
        return crashedRunner(runners).map(r -> new ClientStatus.Crashed(infoOf(r),
                crashSummary(r.health().lastCrash().orElseThrow())));
    }

    /**
     * The runner whose current run ended in a crash, newest crash first — what
     * the card shows as crashed and therefore what "Restart" restarts. A crash
     * older than its runner's last start belongs to an earlier run and does not
     * count, even if it is the newest crash on the client.
     */
    static Optional<ScriptRunner> crashedRunner(List<ScriptRunner> runners) {
        ScriptRunner newest = null;
        LastCrash newestCrash = null;
        for (ScriptRunner r : runners) {
            Optional<LastCrash> crash = r.health().lastCrash();
            Instant started = r.lastStartedAt();
            if (r.isRunning() || crash.isEmpty() || started == null || crash.get().when().isBefore(started)) {
                continue;
            }
            if (newestCrash == null || crash.get().when().isAfter(newestCrash.when())) {
                newest = r;
                newestCrash = crash.get();
            }
        }
        return Optional.ofNullable(newest);
    }

    static String crashSummary(LastCrash crash) {
        String type = crash.cause() != null ? crash.cause().getClass().getSimpleName() : "Error";
        return type + " in " + phaseMethod(crash.phase());
    }

    private static String phaseMethod(Phase phase) {
        return switch (phase) {
            case ON_START -> "onStart()";
            case ON_LOOP -> "onLoop()";
            case ON_STOP -> "onStop()";
            case ON_CONFIG_UPDATE -> "onConfigUpdate()";
        };
    }

    private static ScriptInfo lastScriptInfo(Connection conn) {
        return conn.getRuntime().getRunners().stream()
                .filter(r -> r.lastStartedAt() != null)
                .max(Comparator.comparing(ScriptRunner::lastStartedAt))
                .map(LiveClientBoard::infoOf)
                .orElse(null);
    }

    private boolean hasGivenUp(Connection conn) {
        return !conn.isAlive() && attemptsIfGaveUp(conn.currentReconnectState()) > 0;
    }

    /** Attempts made before giving up, or {@code 0} for any other state. */
    private static int attemptsIfGaveUp(ReconnectState state) {
        if (state == null) {
            return 0;
        }
        return switch (state) {
            case ReconnectState.GivingUp g -> Math.max(1, g.attempts());
            case ReconnectState.Connected ignored -> 0;
            case ReconnectState.Disconnected ignored -> 0;
            case ReconnectState.Reconnecting ignored -> 0;
        };
    }

    // ── Script details ─────────────────────────────────────────────────────

    private static ScriptInfo infoOf(ScriptRunner runner) {
        return infoOf(runner.getScript(), runner.getManifest(), runner.getScriptName());
    }

    private static ScriptInfo infoOf(BotScript script) {
        ScriptManifest manifest = script.getClass().getAnnotation(ScriptManifest.class);
        return infoOf(script, manifest, nameOf(script));
    }

    /** The name the runtime registers {@code script} under: its manifest name, else its class name. */
    static String nameOf(BotScript script) {
        ScriptManifest manifest = script.getClass().getAnnotation(ScriptManifest.class);
        return manifest != null ? manifest.name() : script.getClass().getSimpleName();
    }

    private static ScriptInfo infoOf(BotScript script, ScriptManifest manifest, String name) {
        return ScriptInfo.of(manifest, name, safeFields(script).size(), safeHasUi(script));
    }

    /** Script code: a throwing {@code getConfigFields()} must not take the frame down. */
    private static List<ConfigField> safeFields(BotScript script) {
        try {
            List<ConfigField> fields = script.getConfigFields();
            return fields != null ? fields : List.of();
        } catch (RuntimeException e) {
            log.debug("getConfigFields() threw for {}: {}", script.getClass().getName(), e.toString());
            return List.of();
        }
    }

    private static boolean safeHasUi(BotScript script) {
        return safeUi(script) != null;
    }

    /** Script code: a throwing {@code getUI()} reads as "no UI" rather than escaping the frame. */
    private static ScriptUI safeUi(BotScript script) {
        try {
            return script.getUI();
        } catch (RuntimeException e) {
            log.debug("getUI() threw for {}: {}", script.getClass().getName(), e.toString());
            return null;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private Connection find(String clientId) {
        for (Connection conn : ctx.getConnections()) {
            if (conn.getName().equals(clientId)) {
                return conn;
            }
        }
        return null;
    }

    private static String displayName(Connection conn) {
        String account = conn.getAccountName();
        return account != null && !account.isBlank() ? account : conn.getName();
    }

    /** The client's world, or {@code 0} (the card's "no world") when it is not in one. */
    private static int worldOf(Connection conn) {
        return conn.getWorldId().orElse(0);
    }

    private final class Actions implements ClientActions {

        @Override
        public void startScript(String clientId, ScriptEntry script) {
            Connection conn = find(clientId);
            if (conn != null) {
                scriptFor(script).ifPresent(conn.getRuntime()::startScript);
            }
        }

        @Override
        public void startSubscription(String clientId, String scriptId) {
            subscriptions.start(clientId, scriptId);
        }

        @Override
        public void stopScript(String clientId) {
            Connection conn = find(clientId);
            if (conn != null) {
                conn.getRuntime().getRunners().stream()
                        .filter(ScriptRunner::isRunning)
                        .forEach(ScriptRunner::stop);
            }
        }

        @Override
        public void restartScript(String clientId) {
            Connection conn = find(clientId);
            if (conn == null) {
                return;
            }
            crashedRunner(conn.getRuntime().getRunners()).ifPresent(ScriptRunner::start);
        }

        /** Returns at once: tearing down and reopening the pipe runs on the command executor. */
        @Override
        public void reconnect(String clientId) {
            cancelledReconnects.remove(clientId);
            deadSinceMillis.remove(clientId);
            commandExecutor.execute(() -> {
                ctx.disconnect(clientId, true);
                ctx.connect(clientId);
            });
        }

        @Override
        public void cancelReconnect(String clientId) {
            Connection conn = find(clientId);
            if (conn != null && conn.getReconnectController() != null) {
                conn.getReconnectController().close();
            }
            cancelledReconnects.add(clientId);
        }

        @Override
        public void viewLog(String clientId) {
            logOpener.accept(clientId);
        }

        @Override
        public void retryHost() {
            for (Connection conn : ctx.getConnections()) {
                if (hasGivenUp(conn)) {
                    reconnect(conn.getName());
                }
            }
        }
    }
}
