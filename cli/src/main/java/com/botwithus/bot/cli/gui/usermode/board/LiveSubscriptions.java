package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.cli.CliContext;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
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
 */
final class LiveSubscriptions {

    private static final Logger log = LoggerFactory.getLogger(LiveSubscriptions.class);

    private final CliContext ctx;
    private final SdnCatalogueRefresher refresher;
    private final SdnInstaller installer;
    private final Supplier<List<LocalScript>> localScripts;
    private final Executor installExecutor;
    /** Only in-flight and failed installs live here; an absent id is decided from the runtime. */
    private final Map<String, SubscriptionState> installs = new ConcurrentHashMap<>();
    private boolean reportedOldLauncher;

    /**
     * @param localScripts the picker's local catalogue, as last loaded
     */
    LiveSubscriptions(CliContext ctx, SdnCatalogueRefresher refresher, SdnInstaller installer,
                      Supplier<List<LocalScript>> localScripts, Executor installExecutor) {
        this.ctx = ctx;
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
                .filter(s -> SubscriptionMatch.isSameScript(s.script().getClass().getName(), s.name(), e))
                .findFirst();
    }

    private static Optional<ScriptRunner> runnerFor(Connection conn, SdnCatalogueEntry e) {
        if (conn == null) {
            return Optional.empty();
        }
        return conn.getRuntime().getRunners().stream()
                .filter(r -> SubscriptionMatch.isSameScript(r.getScript().getClass().getName(),
                        r.getScriptName(), e))
                .findFirst();
    }

    private Optional<BotScript> installedCopy(Connection conn, SdnCatalogueEntry e) {
        Optional<BotScript> registered = runnerFor(conn, e).map(ScriptRunner::getScript);
        return registered.isPresent() ? registered : localCopy(e).map(LocalScript::script);
    }

    // ── Install, off the render thread ─────────────────────────────────────

    private void install(String clientId, SdnCatalogueEntry entry) {
        SdnInstallResult result;
        try {
            result = installer.install(List.of(entry.id()));
        } catch (RuntimeException e) {
            log.warn("SDN: installing {} threw", entry.name(), e);
            result = new SdnInstallResult.Failed(String.valueOf(e.getMessage()));
        }
        settle(clientId, entry, result);
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
                .filter(s -> SubscriptionMatch.isSameScript(s.getClass().getName(), registeredName(s), entry))
                .findFirst();
        if (chosen.isEmpty()) {
            fail(entry, "The launcher delivered " + scripts.size() + " script(s), but not " + entry.name() + ".");
            return;
        }
        installs.remove(entry.id());
        Connection conn = find(clientId);
        if (conn == null || !conn.isAlive()) {
            log.info("SDN: installed {}, but {} has gone; it is ready to start there when it is back",
                    entry.name(), clientId);
            return;
        }
        startOn(conn, chosen.get());
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
        for (Connection conn : new ArrayList<>(ctx.getConnections())) {
            if (conn.isAlive()) {
                live.add(conn);
            }
        }
        return live;
    }

    private Connection find(String clientId) {
        for (Connection conn : new ArrayList<>(ctx.getConnections())) {
            if (conn.getName().equals(clientId)) {
                return conn;
            }
        }
        return null;
    }
}
