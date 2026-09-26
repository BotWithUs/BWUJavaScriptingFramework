package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.sdn.SdnCatalogueEntry;
import com.botwithus.bot.core.sdn.SdnCatalogueRefresher;
import com.botwithus.bot.core.sdn.SdnCatalogueResult;
import com.botwithus.bot.core.sdn.SdnInstallResult;
import com.botwithus.bot.core.sdn.SdnInstaller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The live side of the picker's "Your subscriptions" group: reads the catalogue
 * the Scripts Store panel already keeps fresh, and installs a subscribed script
 * through the same {@link SdnInstaller} path before starting it.
 *
 * <p>There is no second fetch loop. The {@link SdnCatalogueRefresher} is the one
 * the Store panel uses; {@link #group} only nudges it with {@code tick()}, which
 * starts a fetch when one is due and otherwise costs nothing.</p>
 *
 * <p>Installs run on their own virtual thread. {@link SdnInstaller#install} runs
 * one at a time per process, whether the picker or the Store panel asked, so a
 * second click waits behind the first and its row stays "Installing" meanwhile.</p>
 *
 * <p>A delivered script is started only if the runner the client's runtime hands
 * back holds that script. The runtime keys runners by name, so a different
 * script that is already loaded under the same name would otherwise be the one
 * that starts.</p>
 */
final class LiveSubscriptions {

    private static final Logger log = LoggerFactory.getLogger(LiveSubscriptions.class);

    private final Supplier<? extends Collection<Connection>> connections;
    private final SdnCatalogueRefresher refresher;
    private final Function<List<String>, SdnInstallResult> installer;
    private final Supplier<List<LocalScript>> localScripts;
    private final Executor installExecutor;
    /** Only in-flight and failed installs live here; an absent id is decided from the runtime. */
    private final Map<String, SubscriptionState> installs = new ConcurrentHashMap<>();
    private boolean reportedOldLauncher;

    /**
     * @param connections  the host's connections, read fresh on every call
     * @param installer    asks the launcher for scripts by catalogue id and loads them; blocks
     * @param localScripts the picker's local catalogue, as last loaded
     */
    LiveSubscriptions(Supplier<? extends Collection<Connection>> connections, SdnCatalogueRefresher refresher,
                      Function<List<String>, SdnInstallResult> installer, Supplier<List<LocalScript>> localScripts,
                      Executor installExecutor) {
        this.connections = connections;
        this.refresher = refresher;
        this.installer = installer;
        this.localScripts = localScripts;
        this.installExecutor = installExecutor;
    }

    /** The group for {@code clientId}. Render thread; cheap enough to call every frame. */
    SubscriptionGroup group(String clientId) {
        refresher.tick();
        Connection conn = find(clientId);
        SubscriptionGroup group = SubscriptionGroups.of(refresher.shown(), e -> row(conn, e));
        if (group.isHidden() && !reportedOldLauncher) {
            reportedOldLauncher = true;
            log.info("SDN: the launcher does not report subscriptions (it predates the field), "
                    + "so the picker's \"Your subscriptions\" group is hidden. Update the launcher to see it.");
        }
        return group;
    }

    /**
     * Starts the subscribed script {@code scriptId} on {@code clientId}, installing it
     * first if needed. Render thread only.
     */
    void start(String clientId, String scriptId) {
        Optional<SdnCatalogueEntry> entry = catalogueEntry(scriptId);
        if (entry.isEmpty()) {
            log.debug("SDN: {} is no longer in the catalogue; nothing to start", scriptId);
            return;
        }
        Connection conn = find(clientId);
        Optional<BotScript> installed = installedCopy(conn, entry.get());
        if (installed.isPresent()) {
            startOn(conn, installed.get());
            return;
        }
        // Only this (render) thread ever marks an install in flight, and the worker
        // only clears or fails it, so check-then-put cannot start the same install twice.
        SubscriptionState before = installs.get(scriptId);
        if (before != null && !before.isChoosable()) {
            return;
        }
        installs.put(scriptId, new SubscriptionState.Installing());
        installExecutor.execute(() -> install(clientId, entry.get()));
    }

    // ── Rows ───────────────────────────────────────────────────────────────

    private SubscriptionEntry row(Connection conn, SdnCatalogueEntry e) {
        OptionalInt localKey = localCopy(e).stream().mapToInt(LocalScript::key).findFirst();
        return SubscriptionEntry.of(e, stateOf(conn, e, localKey.isPresent()), localKey);
    }

    private SubscriptionState stateOf(Connection conn, SdnCatalogueEntry e, boolean hasLocalCopy) {
        SubscriptionState tracked = installs.get(e.id());
        if (tracked != null && !tracked.isChoosable()) {
            return tracked;
        }
        if (hasLocalCopy || runnerFor(conn, e).isPresent()) {
            return new SubscriptionState.Installed();
        }
        return tracked != null ? tracked : new SubscriptionState.NotInstalled();
    }

    private Optional<LocalScript> localCopy(SdnCatalogueEntry e) {
        return localScripts.get().stream()
                .filter(s -> SubscriptionMatch.isLoadedCopy(s.script().getClass().getName(), e))
                .findFirst();
    }

    private static Optional<ScriptRunner> runnerFor(Connection conn, SdnCatalogueEntry e) {
        if (conn == null) {
            return Optional.empty();
        }
        return conn.getRuntime().getRunners().stream()
                .filter(r -> SubscriptionMatch.isLoadedCopy(r.getScript().getClass().getName(), e))
                .findFirst();
    }

    private Optional<BotScript> installedCopy(Connection conn, SdnCatalogueEntry e) {
        Optional<BotScript> registered = runnerFor(conn, e).map(ScriptRunner::getScript);
        return registered.isPresent() ? registered : localCopy(e).map(LocalScript::script);
    }

    // ── Install, off the render thread ─────────────────────────────────────

    /**
     * Everything after the click runs inside one boundary: whatever throws, the row
     * ends up failed with a message rather than stuck on "Installing…".
     */
    private void install(String clientId, SdnCatalogueEntry entry) {
        try {
            settle(clientId, entry, installer.apply(List.of(entry.id())));
        } catch (RuntimeException e) {
            log.warn("SDN: installing {} threw", entry.name(), e);
            fail(entry, "Something went wrong while installing: " + e.getMessage());
        }
    }

    private void settle(String clientId, SdnCatalogueEntry entry, SdnInstallResult result) {
        switch (result) {
            case SdnInstallResult.Installed installed -> startDelivered(clientId, entry, installed.scripts());
            case SdnInstallResult.NothingSelected ignored -> fail(entry, "Nothing was requested from the launcher.");
            case SdnInstallResult.DeliveryDisabled ignored -> fail(entry,
                    "This host was not started by the launcher, so it cannot install scripts. "
                            + "Start it from the BotWithUs launcher.");
            case SdnInstallResult.CourierUnavailable ignored -> fail(entry,
                    "The launcher did not deliver the script. Check it is still running, then try again.");
            case SdnInstallResult.Failed failed -> fail(entry, failed.reason());
        }
    }

    /**
     * Registers the delivery on every live client, as the Store panel does, then
     * starts the chosen script on the client it was picked for.
     */
    private void startDelivered(String clientId, SdnCatalogueEntry entry, List<BotScript> scripts) {
        for (Connection conn : liveConnections()) {
            scripts.forEach(conn.getRuntime()::registerScript);
        }
        Optional<BotScript> chosen = scripts.stream()
                .filter(s -> SubscriptionMatch.isDeliveryOf(s.getClass().getName(), registeredName(s), entry))
                .findFirst();
        if (chosen.isEmpty()) {
            fail(entry, "The launcher delivered " + scripts.size() + " script(s), but not " + entry.name() + ".");
            return;
        }
        Connection conn = find(clientId);
        if (conn == null || !conn.isAlive()) {
            installs.remove(entry.id());
            log.info("SDN: installed {}, but {} is no longer connected, so it was not started. "
                    + "A reconnect makes a new connection that does not carry this delivery; "
                    + "pick it again there to install and start it.", entry.name(), clientId);
            return;
        }
        startIfDelivered(conn, entry, chosen.get());
    }

    /**
     * Starts {@code delivered} on {@code conn} only if the runner its runtime keeps under
     * that name holds it. {@code registerScript} hands back any active runner with the
     * same name, so a different script loaded under that name would start instead.
     */
    private void startIfDelivered(Connection conn, SdnCatalogueEntry entry, BotScript delivered) {
        ScriptRunner runner = conn.getRuntime().registerScript(delivered);
        if (!holds(runner, delivered)) {
            fail(entry, "A different script named " + runner.getScriptName()
                    + " is already loaded on this client. Stop or remove it, then try again.");
            return;
        }
        installs.remove(entry.id());
        conn.getRuntime().startScript(delivered);
    }

    /**
     * The runner holds this delivery, or an earlier delivery of the same class: a
     * re-install of a script already loaded from the launcher is the same script.
     */
    private static boolean holds(ScriptRunner runner, BotScript delivered) {
        BotScript held = runner.getScript();
        return held == delivered || held.getClass().getName().equals(delivered.getClass().getName());
    }

    private void fail(SdnCatalogueEntry entry, String message) {
        log.info("SDN: could not install {}: {}", entry.name(), message);
        installs.put(entry.id(), new SubscriptionState.Failed(message));
    }

    private static void startOn(Connection conn, BotScript script) {
        if (conn == null || !conn.isAlive()) {
            return;
        }
        conn.getRuntime().startScript(script);
    }

    /** The name a registered runner of {@code script} would carry, without registering it anywhere. */
    private static String registeredName(BotScript script) {
        return LiveClientBoard.nameOf(script);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private Optional<SdnCatalogueEntry> catalogueEntry(String scriptId) {
        return switch (refresher.shown().orElse(null)) {
            case SdnCatalogueResult.Delivered d -> d.entries().stream()
                    .filter(e -> e.id().equals(scriptId))
                    .findFirst();
            case null -> Optional.empty();
            case SdnCatalogueResult.CourierUnavailable ignored -> Optional.empty();
            case SdnCatalogueResult.NotSignedIn ignored -> Optional.empty();
            case SdnCatalogueResult.SubscriptionRequired ignored -> Optional.empty();
            case SdnCatalogueResult.Failed ignored -> Optional.empty();
        };
    }

    private List<Connection> liveConnections() {
        List<Connection> live = new ArrayList<>();
        for (Connection conn : new ArrayList<>(connections.get())) {
            if (conn.isAlive()) {
                live.add(conn);
            }
        }
        return live;
    }

    private Connection find(String clientId) {
        for (Connection conn : new ArrayList<>(connections.get())) {
            if (conn.getName().equals(clientId)) {
                return conn;
            }
        }
        return null;
    }
}
