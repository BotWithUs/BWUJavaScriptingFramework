package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.report.ReportSubject;
import com.botwithus.bot.cli.clients.ClientLifecycle;
import com.botwithus.bot.cli.clients.ClientRecord;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.gui.inspector.InspectorRequest;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject;
import com.botwithus.bot.cli.management.ManagedLinks;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.core.runtime.JarLoadOutcome;
import com.botwithus.bot.core.runtime.ScriptFolder;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.sdn.InstalledScriptsLedger;
import com.botwithus.bot.core.sdn.SdnCatalogueEntry;
import com.botwithus.bot.core.sdn.SdnCatalogueResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * {@link InstalledModel} over the real host: the scripts folder's latest load
 * pass, every connection's runners, the failed-load list and the Store ledger.
 *
 * <p>Nothing here blocks the render thread. Reloading, starting, stopping,
 * watching and opening the folder all run on the host's command executor; the
 * view answers from what the host says now. The view is rebuilt at most once
 * per {@code maxAge}, and at once after an action, so the sidebar badge and the
 * page share one build per frame.</p>
 */
public final class LiveInstalledModel implements InstalledModel {

    private static final Logger log = LoggerFactory.getLogger(LiveInstalledModel.class);

    /**
     * What the model reads and where it sends work.
     *
     * @param ledger       the Store installs this host recorded
     * @param catalogue    what the Store catalogue shows now; empty before its first answer
     * @param inspector    opens the shared config inspector
     * @param executor     runs everything that stops, starts or loads scripts
     * @param folderOpener shows a folder in the system file browser; called on the render thread
     * @param clock        stamps reloads and ages files and runs; its zone formats them
     * @param scriptsDir   the scripts folder
     * @param folderLabel  that folder as the page shows it, such as {@code "scripts/"}
     * @param maxAge       how long one built view is reused
     */
    public record Deps(CliContext ctx, InstalledScriptsLedger ledger,
                       Supplier<Optional<SdnCatalogueResult>> catalogue, Consumer<InspectorRequest> inspector,
                       Executor executor, Consumer<Path> folderOpener, Clock clock, Path scriptsDir,
                       String folderLabel, Duration maxAge) {}

    private final Deps deps;
    private final CliContext ctx;
    private final AtomicBoolean isReloading = new AtomicBoolean();
    /** Written by the executor when a reload finishes; {@code null} until one has. */
    private volatile Instant reloadedAt;
    /** Set from any thread when the host changed under the cached view. */
    private volatile boolean isStale = true;
    private InstalledView cached;
    private Instant cachedAt = Instant.MIN;
    /** Opens the report dialog; a no-op until {@link #setReportOpener} is called. */
    private Consumer<ReportSubject> reportOpener = subject -> { };

    public LiveInstalledModel(Deps deps) {
        this.deps = deps;
        this.ctx = deps.ctx();
    }

    /**
     * Shows a folder in the system file browser, creating it first when it is
     * missing. The browser call can block, so it runs on {@code executor}.
     */
    public static Consumer<Path> desktopOpener(Executor executor) {
        return dir -> executor.execute(() -> {
            if (!Desktop.isDesktopSupported()) {
                log.warn("Cannot open {}: no desktop to open it on", dir);
                return;
            }
            try {
                Files.createDirectories(dir);
                Desktop.getDesktop().open(dir.toFile());
            } catch (IOException | RuntimeException e) {
                log.warn("Could not open {}: {}", dir, e.toString());
            }
        });
    }

    @Override
    public InstalledView view() {
        Instant now = deps.clock().instant();
        if (cached == null || isStale || !now.isBefore(cachedAt.plus(deps.maxAge()))) {
            isStale = false;
            cached = build(now);
            cachedAt = now;
        }
        return cached;
    }

    private InstalledView build(Instant now) {
        List<Connection> connections = List.copyOf(ctx.getConnections());
        List<InstalledScript> rows = LiveRows.build(new LiveRows.Inputs(ctx.getLastLoadReport().results(),
                connections, deps.ledger().all(), catalogueById(), now, deps.clock().getZone(), this::managedBy));
        List<JarLoadOutcome> pass = List.copyOf(ctx.getLastLoadReport().results());
        List<LoadProblem> problems = LoadProblems.of(ctx.getLoadIssues().issues(ScriptFolder.SCRIPTS), pass,
                deps.folderLabel());
        return new InstalledView(header(), rows, problems, clientChoices(connections));
    }

    /**
     * Every client the Start-on dialog can offer: each connection, live or
     * dropped, then each client the registry remembers that has none, such as
     * an account whose game is closed.
     */
    private List<ClientChoice> clientChoices(List<Connection> connections) {
        List<ClientChoice> choices = new ArrayList<>();
        Set<ClientKey> keys = new HashSet<>();
        Set<String> pipes = new HashSet<>();
        for (Connection c : connections) {
            ClientKey key = ctx.clientKeyOf(c.getName());
            keys.add(key);
            pipes.add(c.getName());
            choices.add(new ClientChoice(c.getName(), RunnerDetails.clientName(c), c.isAlive(),
                    RunnerDetails.offlineNote(c), key));
        }
        for (ClientRecord client : ctx.getClientRegistry().clients()) {
            boolean hasConnection = keys.contains(client.key()) || client.pipe().filter(pipes::contains).isPresent();
            if (!hasConnection && !client.lifecycle().isOpen()) {
                choices.add(new ClientChoice(client.key().value(), client.name().orElse(client.key().value()),
                        false, closedNote(client.lifecycle()), client.key()));
            }
        }
        return choices;
    }

    private static String closedNote(ClientLifecycle lifecycle) {
        return switch (lifecycle) {
            case ClientLifecycle.NotResponding _ -> "not responding";
            case ClientLifecycle.Closed _ -> "game closed";
            case ClientLifecycle.Identifying _, ClientLifecycle.Connected _, ClientLifecycle.Resuming _ ->
                    "not connected";
        };
    }

    /**
     * The management scripts that name {@code script} on {@code conn}'s
     * account, directly or by a group; see {@link ManagedLinks}.
     */
    private List<String> managedBy(Connection conn, String script) {
        return ctx.clientKeyOf(conn.getName()).accountUuid()
                .map(uuid -> ManagedLinks.names(ctx.getManagementTargets(), uuid, script))
                .orElse(List.of());
    }

    private InstalledHeader header() {
        HostSettings settings = ctx.getSettings();
        boolean isRestart = settings != null && settings.get(SettingKeys.RESTART_AFTER_RELOAD);
        Optional<String> reloaded = Optional.ofNullable(reloadedAt)
                .map(t -> WhenText.clock(t, deps.clock().getZone()));
        return new InstalledHeader(deps.folderLabel(), reloaded, isReloading.get(), ctx.isWatcherRunning(),
                isRestart);
    }

    private Map<String, SdnCatalogueEntry> catalogueById() {
        return deps.catalogue().get()
                .map(LiveInstalledModel::entriesOf)
                .orElse(Map.of());
    }

    private static Map<String, SdnCatalogueEntry> entriesOf(SdnCatalogueResult result) {
        return switch (result) {
            case SdnCatalogueResult.Delivered d -> d.entries().stream()
                    .collect(Collectors.toMap(SdnCatalogueEntry::id, e -> e, (a, b) -> a, LinkedHashMap::new));
            case SdnCatalogueResult.CourierUnavailable ignored -> Map.of();
            case SdnCatalogueResult.NotSignedIn ignored -> Map.of();
            case SdnCatalogueResult.SubscriptionRequired ignored -> Map.of();
            case SdnCatalogueResult.Failed ignored -> Map.of();
        };
    }

    // ── Actions ────────────────────────────────────────────────────────────

    @Override
    public void setWatching(boolean isWatching) {
        submit("watch", () -> {
            if (isWatching) {
                ctx.startScriptWatcher();
            } else {
                ctx.stopScriptWatcher();
            }
        });
    }

    @Override
    public void setRestartAfterReload(boolean isRestartAfterReload) {
        HostSettings settings = ctx.getSettings();
        if (settings != null) {
            settings.set(SettingKeys.RESTART_AFTER_RELOAD, isRestartAfterReload);
        }
        isStale = true;
    }

    /** Stopping every runner can take seconds per client, so the reload runs on the executor. */
    @Override
    public void reload() {
        if (!isReloading.compareAndSet(false, true)) {
            return;
        }
        isStale = true;
        submit("reload", () -> {
            try {
                ctx.reloadAllScripts(ctx.afterReloadSetting());
                reloadedAt = deps.clock().instant();
            } finally {
                isReloading.set(false);
            }
        });
    }

    @Override
    public void openFolder() {
        deps.folderOpener().accept(deps.scriptsDir());
    }

    /**
     * Starts {@code key} now on each of {@code clientIds} that is connected, and
     * queues it for when they are back on those the dialog offers that for.
     */
    @Override
    public void startOn(String key, List<String> clientIds) {
        List<String> ids = List.copyOf(clientIds);
        List<String> accounts = whenBackAccounts(key, ids);
        submit("start " + key, () -> {
            startNow(key, ids);
            accounts.forEach(uuid -> ctx.startWhenBack(uuid, key));
        });
    }

    /**
     * The accounts among {@code ids} whose client is not connected and can have
     * {@code key} wait for it: the same rule the Start-on dialog ticks by.
     */
    private List<String> whenBackAccounts(String key, List<String> ids) {
        InstalledView now = view();
        Map<String, ClientChoice> byId = now.clients().stream()
                .collect(Collectors.toMap(ClientChoice::clientId, c -> c, (a, b) -> a));
        return now.find(key)
                .map(script -> StartTargets.of(script, now.clients()))
                .orElse(List.of())
                .stream()
                .filter(t -> t.startsWhenBack() && ids.contains(t.clientId()))
                .map(t -> byId.get(t.clientId()).key().accountUuid())
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public void stopEverywhere(String key) {
        submit("stop " + key, () -> ctx.getConnections().stream()
                .map(conn -> conn.getRuntime().findRunner(key))
                .filter(r -> r != null && r.isRunning())
                .forEach(ScriptRunner::stop));
    }

    @Override
    public void stop(String key, String clientId) {
        submit("stop " + key, () -> find(clientId)
                .map(conn -> conn.getRuntime().findRunner(key))
                .ifPresent(ScriptRunner::stop));
    }

    @Override
    public void run(String key, String clientId) {
        startOn(key, List.of(clientId));
    }

    @Override
    public void openSettings(String key, String clientId) {
        find(clientId).map(conn -> conn.getRuntime().findRunner(key)).ifPresent(runner -> {
            BotScript script = runner.getScript();
            deps.inspector().accept(InspectorRequest.settings(
                    new InspectorSubject.ClientScript(clientId, runner.getScriptName()),
                    RunnerDetails.settingsCount(script) > 0, RunnerDetails.hasUi(script)));
        });
    }

    /** Starts {@code key} on each client in {@code ids} that is connected now. */
    private void startNow(String key, List<String> ids) {
        for (String id : ids) {
            find(id).filter(Connection::isAlive).ifPresent(conn -> startOnClient(conn, key));
        }
    }

    /**
     * Starts the client's own runner of {@code key}, or, when it has none, a
     * fresh copy from the scripts folder: each runtime gets its own instance. A
     * Store script reaches a client only by being installed there, so one with
     * no runner and no JAR is left alone.
     */
    private void startOnClient(Connection conn, String key) {
        ScriptRunner runner = conn.getRuntime().findRunner(key);
        if (runner != null) {
            if (!runner.isRunning()) {
                runner.start();
            }
            return;
        }
        ctx.loadScripts().stream()
                .filter(s -> JarLoadOutcome.nameOf(s).equals(key))
                .findFirst()
                .ifPresentOrElse(conn.getRuntime()::startScript, () -> log.info(
                        "Not starting {} on {}: no JAR in the scripts folder declares it", key, conn.getName()));
    }

    @Override
    public void reportProblem(String key, String clientId) {
        reportOpener.accept(new ReportSubject(clientId, key));
    }

    /** Has "Report a problem" open the report dialog through {@code opener}. Render thread. */
    public void setReportOpener(Consumer<ReportSubject> opener) {
        this.reportOpener = opener;
    }

    private Optional<Connection> find(String clientId) {
        return ctx.getConnections().stream().filter(c -> c.getName().equals(clientId)).findFirst();
    }

    private void submit(String what, Runnable task) {
        deps.executor().execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.warn("Installed scripts: {} failed: {}", what, e.toString());
            } finally {
                isStale = true;
            }
        });
    }
}
