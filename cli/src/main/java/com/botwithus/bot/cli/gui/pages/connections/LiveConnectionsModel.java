package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.clients.ClientLifecycle;
import com.botwithus.bot.cli.clients.ClientRecord;
import com.botwithus.bot.cli.clients.ClientRegistry;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.InvalidSettingException;
import com.botwithus.bot.cli.settings.ReconnectPolicySettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.core.config.ScriptProfileStore;
import com.botwithus.bot.core.rpc.ReconnectController;
import com.botwithus.bot.core.rpc.ReconnectController.RetryOutcome;
import com.botwithus.bot.core.rpc.ReconnectPolicy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Connections page over the live host: the client registry for who is
 * known, the connection table for who is held, the host settings for the
 * header, and the account profiles for "Resume after restart".
 *
 * <p>Reads run on the render thread and touch no pipe and no file, except the
 * selected account's profile, which is read at most once a second. Everything
 * that blocks runs elsewhere: scans on {@code scanExecutor} (they probe every
 * pipe, and can log a client into the lobby, which takes seconds), and connects,
 * disconnects, forgets and profile writes on {@code commandExecutor}, the same
 * queue console commands use.</p>
 */
public final class LiveConnectionsModel implements ConnectionsModel {

    private static final Logger log = LoggerFactory.getLogger(LiveConnectionsModel.class);

    /** How long a profile read for the detail pane is reused. */
    private static final Duration PROFILE_REFRESH = Duration.ofSeconds(1);
    private static final char GLOB = '*';

    /** The two things the page does through the {@code connect} command, which it shares with the console. */
    public interface PipeCommands {

        /** Probes every pipe under {@code prefix}, as {@code connect scan} does. Blocks. */
        ScanOutcome scan(String prefix);

        /** Connects to {@code pipe} and starts the account's scripts, as {@code connect <pipe>} does. Blocks. */
        void connect(String pipe);
    }

    /**
     * What a scan found.
     *
     * @param message the command's one-line summary, such as "Found 3 pipe(s)."
     */
    public record ScanOutcome(List<FoundPipe> found, String message) {
        public ScanOutcome {
            found = List.copyOf(found);
            Objects.requireNonNull(message, "message");
        }
    }

    private record Profile(boolean isAutoStart, List<String> scripts, Instant readAt) { }

    private final CliContext ctx;
    private final PipeCommands commands;
    private final Executor commandExecutor;
    private final Executor scanExecutor;
    private final Consumer<String> clipboard;
    private final Clock clock;
    private final LinkStatsCache stats = new LinkStatsCache();
    /** Render thread only. */
    private final Map<String, Profile> profiles = new HashMap<>();
    private final AtomicBoolean isScanning = new AtomicBoolean();
    private volatile List<FoundPipe> scanned = List.of();
    private volatile Optional<String> scanMessage = Optional.empty();

    /**
     * @param commandExecutor runs connects, disconnects, forgets and profile writes
     * @param scanExecutor    runs scans
     * @param clipboard       puts text on the system clipboard
     */
    public LiveConnectionsModel(CliContext ctx, PipeCommands commands, Executor commandExecutor,
                                Executor scanExecutor, Consumer<String> clipboard, Clock clock) {
        this.ctx = ctx;
        this.commands = commands;
        this.commandExecutor = commandExecutor;
        this.scanExecutor = scanExecutor;
        this.clipboard = clipboard;
        this.clock = clock;
    }

    // ── Reading ────────────────────────────────────────────────────────────

    @Override
    public ConnectionsView view() {
        Instant now = clock.instant();
        ClientRegistry registry = ctx.getClientRegistry();
        List<ClientRecord> clients = registry.clients();
        List<Connection> connections = ctx.getConnections();
        ConnectionRows.Reading reading = new ConnectionRows.Reading(clients, scanned,
                connections.stream().map(Connection::getName).collect(Collectors.toSet()),
                stats.read(connections, now), Optional.ofNullable(ctx.getActiveConnectionName()),
                Optional.ofNullable(ctx.getMountedConnectionName()), droppedAt(clients, registry), now);
        HostSettings settings = ctx.getSettings();
        return new ConnectionsView(ConnectionRows.build(reading),
                setting(settings, SettingKeys.AUTO_CONNECT.defaultValue(), s -> s.get(SettingKeys.AUTO_CONNECT)),
                pipePrefix(),
                Duration.ofMillis(setting(settings, SettingKeys.SCAN_INTERVAL_MS.defaultValue(),
                        s -> s.get(SettingKeys.SCAN_INTERVAL_MS))),
                isScanning.get(), scanMessage);
    }

    @Override
    public int notResponding() {
        return (int) ctx.getClientRegistry().clients().stream()
                .filter(client -> isNotResponding(client.lifecycle()))
                .count();
    }

    private static boolean isNotResponding(ClientLifecycle lifecycle) {
        return switch (lifecycle) {
            case ClientLifecycle.NotResponding _ -> true;
            case ClientLifecycle.Identifying _, ClientLifecycle.Connected _, ClientLifecycle.Resuming _,
                 ClientLifecycle.Closed _ -> false;
        };
    }

    @Override
    public ConnectionDetail detail(ConnectionRow row) {
        Optional<Profile> profile = row.accountUuid().map(this::profile);
        List<ScriptChip> scripts = ClientScripts.of(
                row.pipe().flatMap(this::connection).map(conn -> conn.getRuntime().getRunners()).orElse(List.of()),
                profile.map(Profile::scripts).orElse(List.of()));
        return new ConnectionDetail(row, profile.map(Profile::isAutoStart),
                row.key().map(key -> Timeline.of(ctx.getClientRegistry().history(key))).orElse(List.of()),
                scripts, groupsOf(row), ConnectionText.policy(policy()));
    }

    /** When each not-responding client stopped answering, from its history. */
    private static Map<ClientKey, Instant> droppedAt(List<ClientRecord> clients, ClientRegistry registry) {
        Map<ClientKey, Instant> dropped = new HashMap<>();
        for (ClientRecord client : clients) {
            switch (client.lifecycle()) {
                case ClientLifecycle.NotResponding _ -> Timeline.droppedAt(registry.history(client.key()))
                        .ifPresent(at -> dropped.put(client.key(), at));
                case ClientLifecycle.Identifying _, ClientLifecycle.Connected _, ClientLifecycle.Resuming _,
                     ClientLifecycle.Closed _ -> { }
            }
        }
        return dropped;
    }

    /** Groups hold accounts, so a client known only by its pipe is in none. */
    private List<String> groupsOf(ConnectionRow row) {
        return row.accountUuid()
                .map(uuid -> ctx.getGroupStore().groupsOf(uuid).stream()
                        .map(ClientGroup::name)
                        .sorted()
                        .toList())
                .orElse(List.of());
    }

    private ReconnectPolicy policy() {
        HostSettings settings = ctx.getSettings();
        return settings != null ? ReconnectPolicySettings.read(settings) : ReconnectPolicy.DEFAULT;
    }

    private Profile profile(String uuid) {
        Instant now = clock.instant();
        Profile cached = profiles.get(uuid);
        if (cached != null && now.isBefore(cached.readAt().plus(PROFILE_REFRESH))) {
            return cached;
        }
        ScriptProfileStore store = ctx.getProfileStore();
        Profile fresh = store == null
                ? new Profile(false, List.of(), now)
                : new Profile(store.isAutoStart(uuid), store.getAccountScripts(uuid), now);
        profiles.put(uuid, fresh);
        return fresh;
    }

    private Optional<Connection> connection(String pipe) {
        return ctx.getConnections().stream().filter(conn -> conn.getName().equals(pipe)).findFirst();
    }

    private String pipePrefix() {
        return setting(ctx.getSettings(), SettingKeys.PIPE_PREFIX.defaultValue(), s -> s.get(SettingKeys.PIPE_PREFIX));
    }

    private static <T> T setting(HostSettings settings, T fallback,
                                 Function<HostSettings, T> read) {
        return settings != null ? read.apply(settings) : fallback;
    }

    // ── Scanning and connecting ────────────────────────────────────────────

    @Override
    public void scan() {
        if (!isScanning.compareAndSet(false, true)) {
            return;
        }
        String prefix = pipePrefix();
        scanExecutor.execute(() -> runScan(prefix));
    }

    private void runScan(String prefix) {
        try {
            ScanOutcome outcome = commands.scan(prefix);
            scanned = outcome.found();
            scanMessage = Optional.of(outcome.message());
        } catch (RuntimeException e) {
            log.warn("Pipe scan failed: {}", e.toString());
            scanMessage = Optional.of("Scan failed: " + e.getMessage());
        } finally {
            isScanning.set(false);
        }
    }

    @Override
    public void connect(ConnectionRow row) {
        if (row.can(RowAction.CONNECT)) {
            row.pipe().ifPresent(pipe -> commandExecutor.execute(() -> commands.connect(pipe)));
        }
    }

    @Override
    public void connectAllFound() {
        List<String> pipes = view().found().stream()
                .filter(row -> row.can(RowAction.CONNECT))
                .flatMap(row -> row.pipe().stream())
                .toList();
        if (!pipes.isEmpty()) {
            commandExecutor.execute(() -> pipes.forEach(commands::connect));
        }
    }

    // ── Row actions ────────────────────────────────────────────────────────

    @Override
    public void disconnect(ConnectionRow row) {
        if (row.can(RowAction.DISCONNECT)) {
            row.pipe().ifPresent(pipe -> commandExecutor.execute(() -> ctx.disconnect(pipe, true)));
        }
    }

    /** Signals the reconnect controller; only a dead pipe with nothing to retry is rebuilt, off this thread. */
    @Override
    public void retryNow(ConnectionRow row) {
        if (!row.can(RowAction.RETRY_NOW)) {
            return;
        }
        Optional<Connection> conn = row.pipe().flatMap(this::connection);
        if (conn.isEmpty()) {
            return;
        }
        ReconnectController controller = conn.get().getReconnectController();
        RetryOutcome outcome = controller != null ? controller.retryNow() : RetryOutcome.CLOSED;
        switch (outcome) {
            case WOKEN, RESTARTED -> { }
            case CLIENT_GONE -> log.info("'{}' cannot be retried: its game client has exited", row.label());
            case NOT_NEEDED, CLOSED -> rebuildIfDead(conn.get());
        }
    }

    private void rebuildIfDead(Connection conn) {
        if (conn.isAlive()) {
            return;
        }
        String pipe = conn.getName();
        commandExecutor.execute(() -> {
            ctx.disconnect(pipe, true);
            commands.connect(pipe);
        });
    }

    @Override
    public void stopRetrying(ConnectionRow row) {
        if (row.can(RowAction.STOP_RETRYING)) {
            row.pipe().flatMap(this::connection)
                    .map(Connection::getReconnectController)
                    .ifPresent(ReconnectController::stopRetrying);
        }
    }

    @Override
    public void forget(ConnectionRow row) {
        if (row.can(RowAction.FORGET)) {
            row.key().ifPresent(key -> commandExecutor.execute(() -> ctx.forget(key)));
        }
    }

    @Override
    public void setConsoleTarget(ConnectionRow row) {
        if (row.can(RowAction.CONSOLE_TARGET)) {
            row.pipe().ifPresent(ctx::setActive);
        }
    }

    @Override
    public void toggleOutputFilter(ConnectionRow row) {
        if (!row.can(RowAction.OUTPUT_FILTER) || row.pipe().isEmpty()) {
            return;
        }
        String pipe = row.pipe().get();
        if (pipe.equals(ctx.getMountedConnectionName())) {
            ctx.unmount();
        } else {
            ctx.mount(pipe);
        }
    }

    @Override
    public void clearOutputFilter() {
        ctx.unmount();
    }

    // ── Header and detail settings ─────────────────────────────────────────

    @Override
    public void setAutoConnect(boolean isOn) {
        HostSettings settings = ctx.getSettings();
        if (settings != null) {
            settings.set(SettingKeys.AUTO_CONNECT, isOn);
        }
    }

    /** Takes the prefix with or without the {@code *} the field shows after it. */
    @Override
    public Optional<String> setPipePrefix(String prefix) {
        HostSettings settings = ctx.getSettings();
        if (settings == null) {
            return Optional.of("Settings are not available.");
        }
        String bare = stripGlob(prefix.strip());
        try {
            settings.set(SettingKeys.PIPE_PREFIX, bare);
            return Optional.empty();
        } catch (InvalidSettingException e) {
            return Optional.of(reasonOf(e));
        }
    }

    private static String stripGlob(String prefix) {
        int end = prefix.length();
        while (end > 0 && prefix.charAt(end - 1) == GLOB) {
            end--;
        }
        return prefix.substring(0, end);
    }

    /** The exception's message without the setting's internal name in front. */
    private static String reasonOf(InvalidSettingException e) {
        String message = e.getMessage();
        String reason = message.startsWith(e.settingName())
                ? message.substring(e.settingName().length()).strip()
                : message;
        return reason.isEmpty() ? message : Character.toUpperCase(reason.charAt(0)) + reason.substring(1);
    }

    /** Takes effect in the pane at once; the profile file is written on the command executor. */
    @Override
    public void setResumeAfterRestart(ConnectionRow row, boolean isOn) {
        ScriptProfileStore store = ctx.getProfileStore();
        if (store == null || row.accountUuid().isEmpty()) {
            return;
        }
        String uuid = row.accountUuid().get();
        Profile current = profile(uuid);
        profiles.put(uuid, new Profile(isOn, current.scripts(), clock.instant()));
        commandExecutor.execute(() -> store.setAutoStart(uuid, isOn));
    }

    @Override
    public void copyUuid(ConnectionRow row) {
        row.accountUuid().ifPresent(clipboard);
    }
}
