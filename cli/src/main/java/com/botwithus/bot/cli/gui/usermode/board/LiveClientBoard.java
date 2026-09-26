package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.model.ItemType;
import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.core.impl.GameAPIImpl;
import com.botwithus.bot.core.impl.MapHelper;
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
import java.util.function.Consumer;

/**
 * {@link ClientBoard} over the host's real connections.
 *
 * <p>Two pieces of card state have no home in the runtime and are kept here, on
 * the UI thread: when a client was first seen dead (for "No reply for 0:42"), and
 * which reconnects the user cancelled. Cancelling closes the controller, but its
 * last published state stays {@code Reconnecting}; without the second set the card
 * would claim to be retrying forever.</p>
 */
public final class LiveClientBoard implements ClientBoard {

    private static final Logger log = LoggerFactory.getLogger(LiveClientBoard.class);

    private static final String WORLD_KEY = "world_id";
    private static final String NO_ITEM_NAME = "null";

    private final CliContext ctx;
    private final Consumer<String> logOpener;
    private final Clock clock;
    private final Actions actions = new Actions();

    private final Map<String, Long> deadSinceMillis = new HashMap<>();
    private final Set<String> cancelledReconnects = new HashSet<>();
    private final Map<Integer, Optional<String>> itemNames = new HashMap<>();
    private final LiveSubscriptions subscriptions;
    private List<BotScript> catalogScripts = List.of();
    private List<LocalScript> localScripts = List.of();

    /**
     * @param logOpener shows the log for a client; receives the client id
     * @param catalogue the SDN catalogue refresher the Scripts Store panel also reads
     * @param installer installs a subscribed script through the launcher
     */
    public LiveClientBoard(CliContext ctx, Consumer<String> logOpener, Clock clock,
                           SdnCatalogueRefresher catalogue, SdnInstaller installer) {
        this.ctx = ctx;
        this.logOpener = logOpener;
        this.clock = clock;
        this.subscriptions = new LiveSubscriptions(ctx::getConnections, catalogue, installer::install,
                () -> localScripts,
                task -> Thread.ofVirtual().name("sdn-subscription-install").start(task));
    }

    @Override
    public List<ClientView> clients() {
        List<ClientView> views = new ArrayList<>();
        for (Connection conn : new ArrayList<>(ctx.getConnections())) {
            views.add(new ClientView(conn.getName(), displayName(conn), worldOf(conn), statusOf(conn)));
        }
        deadSinceMillis.keySet().retainAll(views.stream().map(ClientView::id).toList());
        return views;
    }

    @Override
    public BoardStatus status() {
        List<Connection> conns = new ArrayList<>(ctx.getConnections());
        boolean allGaveUp = !conns.isEmpty() && conns.stream().allMatch(this::hasGivenUp);
        int attempts = conns.stream()
                .map(Connection::currentReconnectState)
                .mapToInt(LiveClientBoard::attemptsIfGaveUp)
                .max().orElse(0);
        var store = ctx.getProfileStore();
        return new BoardStatus(
                allGaveUp,
                attempts,
                ctx.getActiveConnectionName(),
                store != null && store.isAutoConnect(),
                "\\\\.\\pipe\\" + PipeClient.NAME_PREFIX + "*",
                ctx.isMounted() ? ctx.getMountedConnectionName() : null,
                ctx.isWatcherRunning());
    }

    @Override
    public List<ScriptEntry> catalog() {
        List<BotScript> all = new ArrayList<>(ctx.loadScripts());
        all.addAll(ctx.loadBlueprints());
        catalogScripts = List.copyOf(all);
        List<ScriptEntry> entries = new ArrayList<>();
        List<LocalScript> locals = new ArrayList<>();
        for (int i = 0; i < catalogScripts.size(); i++) {
            ScriptInfo info = infoOf(catalogScripts.get(i));
            entries.add(new ScriptEntry(i, info));
            locals.add(new LocalScript(i, catalogScripts.get(i), info.name()));
        }
        localScripts = List.copyOf(locals);
        return entries;
    }

    @Override
    public SubscriptionGroup subscriptions(String clientId) {
        return subscriptions.group(clientId);
    }

    @Override
    public Optional<InspectorTarget> inspect(String clientId) {
        Connection conn = find(clientId);
        if (conn == null) {
            return Optional.empty();
        }
        return conn.getRuntime().getRunners().stream()
                .filter(ScriptRunner::isRunning)
                .findFirst()
                .map(runner -> targetFor(conn, runner));
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

    /** The newest crash that ended a runner's current run, if any runner has one. */
    private static Optional<ClientStatus> latestCrash(List<ScriptRunner> runners) {
        ScriptRunner newest = null;
        LastCrash newestCrash = null;
        for (ScriptRunner r : runners) {
            Optional<LastCrash> crash = r.health().lastCrash();
            Instant started = r.lastStartedAt();
            if (crash.isEmpty() || started == null || crash.get().when().isBefore(started)) {
                continue;
            }
            if (newestCrash == null || crash.get().when().isAfter(newestCrash.when())) {
                newest = r;
                newestCrash = crash.get();
            }
        }
        if (newest == null) {
            return Optional.empty();
        }
        return Optional.of(new ClientStatus.Crashed(infoOf(newest), crashSummary(newestCrash)));
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
        List<ConfigField> fields = safeFields(script);
        boolean hasUi = safeHasUi(script);
        if (manifest == null) {
            return new ScriptInfo(name, "", "", ScriptCategory.UNCATEGORIZED, "", fields.size(), hasUi);
        }
        return new ScriptInfo(name, manifest.author(), manifest.version(), manifest.category(),
                manifest.description(), fields.size(), hasUi);
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
        try {
            return script.getUI() != null;
        } catch (RuntimeException e) {
            log.debug("getUI() threw for {}: {}", script.getClass().getName(), e.toString());
            return false;
        }
    }

    private InspectorTarget targetFor(Connection conn, ScriptRunner runner) {
        return new InspectorTarget(
                conn.getName(),
                displayName(conn),
                infoOf(runner),
                safeFields(runner.getScript()),
                runner::getCurrentConfig,
                runner::applyConfig,
                runner.getScript().getUI(),
                id -> itemName(conn, id),
                runner::isDisposed);
    }

    /**
     * Item names come from the host's own cache reader (NXTCache, in-process),
     * not the pipe. Memoised, misses included; with no cache every id is empty.
     */
    private Optional<String> itemName(Connection conn, int id) {
        return itemNames.computeIfAbsent(id, k -> lookupItemName(conn.getGameAPI(), k));
    }

    private static Optional<String> lookupItemName(GameAPIImpl api, int id) {
        if (api == null) {
            return Optional.empty();
        }
        try {
            ItemType type = api.getItemType(id);
            if (type == null || type.name() == null || type.name().isBlank()
                    || NO_ITEM_NAME.equals(type.name())) {
                return Optional.empty();
            }
            return Optional.of(type.name());
        } catch (RuntimeException e) {
            log.debug("Item name lookup for {} failed: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private Connection find(String clientId) {
        for (Connection conn : new ArrayList<>(ctx.getConnections())) {
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

    private static int worldOf(Connection conn) {
        Map<String, Object> info = conn.getAccountInfo();
        return info != null ? MapHelper.getIntOr(info, WORLD_KEY, 0) : 0;
    }

    private final class Actions implements ClientActions {

        @Override
        public void startScript(String clientId, ScriptEntry script) {
            Connection conn = find(clientId);
            if (conn == null || script.key() < 0 || script.key() >= catalogScripts.size()) {
                return;
            }
            conn.getRuntime().startScript(catalogScripts.get(script.key()));
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
            conn.getRuntime().getRunners().stream()
                    .filter(r -> r.health().lastCrash().isPresent() && !r.isRunning())
                    .max(Comparator.comparing(r -> r.health().lastCrash().orElseThrow().when()))
                    .ifPresent(ScriptRunner::start);
        }

        @Override
        public void reconnect(String clientId) {
            cancelledReconnects.remove(clientId);
            deadSinceMillis.remove(clientId);
            ctx.disconnect(clientId, true);
            ctx.connect(clientId);
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
            for (Connection conn : new ArrayList<>(ctx.getConnections())) {
                if (hasGivenUp(conn)) {
                    reconnect(conn.getName());
                }
            }
        }
    }
}
