package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.clients.ClientLifecycle;
import com.botwithus.bot.cli.clients.ClientRecord;
import com.botwithus.bot.cli.clients.ClientRegistry;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.management.ManagedLinks;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.ReconnectController;
import com.botwithus.bot.core.rpc.ReconnectController.RetryOutcome;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcMetrics;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.sdn.SdnCatalogueRefresher;
import com.botwithus.bot.core.sdn.SdnInstaller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * {@link ClientBoard} over the host's client registry: one card per client the
 * registry knows, live or remembered, with a row for every script on it.
 *
 * <p>The registry says where each client is; the connection on the client's
 * pipe says what its scripts are doing; the account's script profile says what
 * a closed client will resume. Three pieces of card state have no home in the
 * runtime and are kept here, on the render thread: when each client was first
 * seen not answering (for "No reply for 0:42"), each pipe's RPC average, which
 * is worked out once a second, and each runner's script details, which come
 * from script code.</p>
 *
 * <p>Nothing here blocks the render thread. Rebuilding a connection, forgetting
 * a client, reading and writing script profiles and loading the script catalogue
 * all do pipe or disk work, so they run on the host's command executor;
 * {@link #catalog()} answers from the last completed load. Retrying and stopping
 * only signal the reconnect controller.</p>
 */
public final class LiveClientBoard implements ClientBoard {

    private static final Logger log = LoggerFactory.getLogger(LiveClientBoard.class);

    private final CliContext ctx;
    private final Consumer<ClientKey> logOpener;
    private final Clock clock;
    private final Actions actions = new Actions();

    private final Map<ClientKey, Instant> silentSince = new HashMap<>();
    private final Map<ScriptRunner, ScriptInfo> runnerInfo = new HashMap<>();
    private final RpcAverages rpcAverages = new RpcAverages();
    private final ResumeProfiles profiles;
    private final LiveSubscriptions subscriptions;
    private final Executor commandExecutor;
    private final AtomicBoolean catalogLoadQueued = new AtomicBoolean();
    /** Written by the command executor, read by the render thread. */
    private volatile Catalog loadedCatalog = Catalog.EMPTY;

    /** One load of the installed scripts; the entry keys index {@code scripts}. */
    private record Catalog(List<BotScript> scripts, List<ScriptEntry> entries, List<LocalScript> locals) {
        static final Catalog EMPTY = new Catalog(List.of(), List.of(), List.of());
    }

    /** What one frame reads once and every card shares. */
    private record Frame(Map<String, Connection> byPipe, Instant now, long nowNanos, Set<ScriptRunner> seen) { }

    /**
     * Queues the first catalogue load, so the picker has scripts to offer by the
     * time it is first opened.
     *
     * @param logOpener       shows the log for a client; receives the client's key
     * @param catalogue       the SDN catalogue refresher the Scripts Store panel also reads
     * @param installer       installs a subscribed script through the launcher
     * @param commandExecutor runs the blocking work (reconnects, profile and catalogue
     *                        reads) off the render thread; the one console commands run on
     */
    public LiveClientBoard(CliContext ctx, Consumer<ClientKey> logOpener, Clock clock,
                           SdnCatalogueRefresher catalogue, SdnInstaller installer,
                           Executor commandExecutor) {
        this.ctx = ctx;
        this.logOpener = logOpener;
        this.clock = clock;
        this.commandExecutor = commandExecutor;
        this.profiles = new ResumeProfiles(ctx::getProfileStore, commandExecutor, clock);
        this.subscriptions = new LiveSubscriptions(ctx::getConnections, catalogue, installer::install,
                installer.ledger()::find, () -> loadedCatalog.locals(),
                task -> Thread.ofVirtual().name("sdn-subscription-install").start(task));
        requestCatalogLoad();
    }

    @Override
    public List<ClientView> clients() {
        ClientRegistry registry = ctx.getClientRegistry();
        if (registry == null) {
            return List.of();
        }
        Frame frame = new Frame(connectionsByPipe(), clock.instant(), System.nanoTime(), new HashSet<>());
        List<ClientView> views = new ArrayList<>();
        for (ClientRecord record : registry.clients()) {
            views.add(viewOf(record, frame));
        }
        silentSince.keySet().retainAll(views.stream().map(ClientView::id).toList());
        runnerInfo.keySet().retainAll(frame.seen());
        rpcAverages.retain(frame.byPipe().keySet());
        return views;
    }

    private ClientView viewOf(ClientRecord record, Frame frame) {
        Optional<Connection> conn = record.pipe().map(frame.byPipe()::get);
        Optional<String> account = record.key().accountUuid();
        List<ScriptRow> runnerRows = conn.map(c -> runnerRows(c, account, frame)).orElse(List.of());
        ResumeProfiles.Profile profile = record.key().accountUuid()
                .map(profiles::of)
                .orElse(ResumeProfiles.Profile.NONE);
        List<ScriptRow> remembered = profile.scripts().stream()
                .map(name -> ScriptRow.idle(infoByName(name), new ScriptState.Waiting()))
                .toList();
        OptionalDouble rpc = conn.map(c -> rpcAverage(c, frame.now())).orElse(OptionalDouble.empty());
        ClientViews.Facts facts = new ClientViews.Facts(runnerRows, remembered, profile.autoStart(), rpc,
                silenceOf(record, frame.now()));
        return ClientViews.of(record, facts, frame.now());
    }

    /**
     * One row per runner, each with the management script that names it on
     * this client, if any; see {@link ManagedLinks}.
     */
    private List<ScriptRow> runnerRows(Connection conn, Optional<String> account, Frame frame) {
        List<ScriptRow> rows = new ArrayList<>();
        for (ScriptRunner runner : conn.getRuntime().getRunners()) {
            frame.seen().add(runner);
            ScriptInfo info = runnerInfo.computeIfAbsent(runner, LiveClientBoard::infoOf);
            ScriptRow row = ClientRows.rowOf(runner, info, frame.now(), frame.nowNanos(), clock.getZone());
            rows.add(row.withManagedBy(account.flatMap(uuid ->
                    ManagedLinks.first(ctx.getManagementTargets(), uuid, runner.getScriptName()))));
        }
        return rows;
    }

    private OptionalDouble rpcAverage(Connection conn, Instant now) {
        RpcClient rpc = conn.getRpc();
        RpcMetrics metrics = rpc != null ? rpc.getMetrics() : null;
        if (metrics == null) {
            return OptionalDouble.empty();
        }
        return rpcAverages.of(conn.getName(), metrics::snapshot, now);
    }

    /** When the board first saw the client stop answering; empty while it answers. */
    private Optional<Instant> silenceOf(ClientRecord record, Instant now) {
        return switch (record.lifecycle()) {
            case ClientLifecycle.NotResponding _ -> Optional.of(silentSince.computeIfAbsent(record.key(), k -> now));
            case ClientLifecycle.Identifying _, ClientLifecycle.Connected _, ClientLifecycle.Closed _,
                 ClientLifecycle.Resuming _ -> {
                silentSince.remove(record.key());
                yield Optional.empty();
            }
        };
    }

    /** A saved script's details from the catalogue, or its bare name when it is not installed. */
    private ScriptInfo infoByName(String name) {
        return loadedCatalog.entries().stream()
                .map(ScriptEntry::info)
                .filter(info -> info.name().equals(name))
                .findFirst()
                .orElseGet(() -> ScriptInfo.of(null, name, 0, false));
    }

    private Map<String, Connection> connectionsByPipe() {
        Map<String, Connection> byPipe = new HashMap<>();
        for (Connection conn : ctx.getConnections()) {
            byPipe.put(conn.getName(), conn);
        }
        return byPipe;
    }

    @Override
    public BoardStatus status() {
        List<Connection> conns = ctx.getConnections();
        boolean allGaveUp = !conns.isEmpty() && conns.stream().allMatch(LiveClientBoard::hasGivenUp);
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
    public SubscriptionGroup subscriptions(ClientKey client) {
        return subscriptions.group(pipeOf(client).orElse(""));
    }

    @Override
    public ClientActions actions() {
        return actions;
    }

    // ── Reconnect status ───────────────────────────────────────────────────

    private static boolean hasGivenUp(Connection conn) {
        return !conn.isAlive() && attemptsIfGaveUp(conn.currentReconnectState()) > 0;
    }

    /** Attempts made before giving up, or {@code 0} for any other state. */
    private static int attemptsIfGaveUp(ReconnectState state) {
        if (state == null) {
            return 0;
        }
        return switch (state) {
            case ReconnectState.GivingUp g -> Math.max(1, g.attempts());
            case ReconnectState.Connected _, ReconnectState.Disconnected _, ReconnectState.Reconnecting _ -> 0;
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
        if (script == null) {
            return List.of();
        }
        try {
            List<ConfigField> fields = script.getConfigFields();
            return fields != null ? fields : List.of();
        } catch (RuntimeException e) {
            log.debug("getConfigFields() threw for {}: {}", script.getClass().getName(), e.toString());
            return List.of();
        }
    }

    /** Script code: a throwing {@code getUI()} reads as "no UI" rather than escaping the frame. */
    private static boolean safeHasUi(BotScript script) {
        if (script == null) {
            return false;
        }
        try {
            ScriptUI ui = script.getUI();
            return ui != null;
        } catch (RuntimeException e) {
            log.debug("getUI() threw for {}: {}", script.getClass().getName(), e.toString());
            return false;
        }
    }

    // ── Lookups ────────────────────────────────────────────────────────────

    /** The pipe the registry has the client on, if it has one. */
    private Optional<String> pipeOf(ClientKey client) {
        ClientRegistry registry = ctx.getClientRegistry();
        return registry == null ? Optional.empty() : registry.get(client).flatMap(ClientRecord::pipe);
    }

    /** The connection on the client's pipe, if the host still has one. */
    private Optional<Connection> connectionOf(ClientKey client) {
        return pipeOf(client).flatMap(pipe -> ctx.getConnections().stream()
                .filter(conn -> conn.getName().equals(pipe))
                .findFirst());
    }

    private Optional<ScriptRunner> runnerOf(ClientKey client, String scriptName) {
        return connectionOf(client).flatMap(conn -> conn.getRuntime().getRunners().stream()
                .filter(runner -> runner.getScriptName().equals(scriptName))
                .findFirst());
    }

    private final class Actions implements ClientActions {

        @Override
        public void startScript(ClientKey client, ScriptEntry script) {
            connectionOf(client).ifPresent(conn -> scriptFor(script).ifPresent(conn.getRuntime()::startScript));
        }

        @Override
        public void startSubscription(ClientKey client, String scriptId) {
            pipeOf(client).ifPresent(pipe -> subscriptions.start(pipe, scriptId));
        }

        @Override
        public void stopScript(ClientKey client, String scriptName) {
            runnerOf(client, scriptName).ifPresent(ScriptRunner::stop);
        }

        @Override
        public void runScript(ClientKey client, String scriptName) {
            runnerOf(client, scriptName).filter(runner -> !runner.isRunning()).ifPresent(ScriptRunner::start);
        }

        /** Returns at once: tearing down and reopening the pipe runs on the command executor. */
        @Override
        public void reconnect(ClientKey client) {
            silentSince.remove(client);
            pipeOf(client).ifPresent(pipe -> commandExecutor.execute(() -> {
                ctx.disconnect(pipe, true);
                ctx.connect(pipe);
            }));
        }

        /**
         * Returns at once. Signals the reconnect controller, which retries on its own
         * thread; only a connection with nothing to retry is rebuilt, on the command
         * executor.
         */
        @Override
        public void retryNow(ClientKey client) {
            Optional<Connection> found = connectionOf(client);
            if (found.isEmpty()) {
                return;
            }
            Connection conn = found.get();
            ReconnectController controller = conn.getReconnectController();
            RetryOutcome outcome = controller != null ? controller.retryNow() : RetryOutcome.CLOSED;
            switch (outcome) {
                case WOKEN, RESTARTED -> silentSince.remove(client);
                case CLIENT_GONE -> log.info("'{}' cannot be retried: its game client has exited", client);
                case NOT_NEEDED, CLOSED -> rebuildIfDead(client, conn);
            }
        }

        /** A dead pipe whose controller has nothing to retry is rebuilt from scratch instead. */
        private void rebuildIfDead(ClientKey client, Connection conn) {
            if (!conn.isAlive()) {
                reconnect(client);
            }
        }

        @Override
        public void stopRetrying(ClientKey client) {
            connectionOf(client)
                    .map(Connection::getReconnectController)
                    .ifPresent(ReconnectController::stopRetrying);
        }

        /** Returns at once: the connection stops its scripts on the command executor. */
        @Override
        public void forget(ClientKey client) {
            silentSince.remove(client);
            commandExecutor.execute(() -> ctx.forget(client));
        }

        @Override
        public void viewLog(ClientKey client) {
            logOpener.accept(client);
        }

        @Override
        public void setResumeAfterRestart(ClientKey client, boolean isOn) {
            client.accountUuid().ifPresent(uuid -> profiles.setAutoStart(uuid, isOn));
        }
    }
}
