package com.botwithus.bot.cli;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.cli.settings.Subscription;
import com.botwithus.bot.core.config.ScriptProfileStore;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.runtime.ScriptRunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Orchestrates pipe detection, account identification, and script auto-starting.
 * While the {@code autoConnect} setting is on it scans for pipes in the background,
 * probes account info, and starts the scripts that were previously running for
 * each account. Turning the setting off or on stops or starts the scanner at once.
 */
public class AutoStartManager {

    private static final Logger log = LoggerFactory.getLogger(AutoStartManager.class);

    /** Pause before the first scan, so a host started alongside a client does not race its pipe. */
    private static final Duration INITIAL_SCAN_DELAY = Duration.ofSeconds(1);

    private final CliContext ctx;
    private final ScriptProfileStore profileStore;
    private final HostSettings settings;
    private final Function<String, List<String>> pipeScanner;
    private final Duration initialScanDelay;
    private final AtomicBoolean lobbyStubWarned = new AtomicBoolean(false);
    private final Object scanLock = new Object();
    // Guarded by scanLock.
    private Subscription autoConnectSubscription;
    private volatile Thread scanThread;

    public AutoStartManager(CliContext ctx, ScriptProfileStore profileStore, HostSettings settings) {
        this(ctx, profileStore, settings, PipeClient::scanPipes, INITIAL_SCAN_DELAY);
    }

    /** Test seam: {@code AutoStartManagerTest} swaps the OS pipe listing and the start-up pause. */
    AutoStartManager(CliContext ctx, ScriptProfileStore profileStore, HostSettings settings,
                     Function<String, List<String>> pipeScanner, Duration initialScanDelay) {
        this.ctx = ctx;
        this.profileStore = profileStore;
        this.settings = settings;
        this.pipeScanner = pipeScanner;
        this.initialScanDelay = initialScanDelay;
    }

    /**
     * Starts following the {@code autoConnect} setting: scans in the background
     * now if it is on, and starts or stops the scanner whenever it changes.
     * Idempotent.
     */
    public void start() {
        synchronized (scanLock) {
            if (autoConnectSubscription != null) {
                return;
            }
            autoConnectSubscription = settings.onChange(SettingKeys.AUTO_CONNECT, this::applyAutoConnect);
        }
        applyAutoConnect(settings.get(SettingKeys.AUTO_CONNECT));
    }

    /** Stops following the setting and stops the scanner. Used on shutdown. */
    public void stop() {
        synchronized (scanLock) {
            if (autoConnectSubscription != null) {
                autoConnectSubscription.close();
                autoConnectSubscription = null;
            }
        }
        stopScanner();
    }

    /** {@code true} while the background pipe scanner is running. */
    public boolean isScanning() {
        return scanThread != null;
    }

    private void applyAutoConnect(boolean enabled) {
        if (enabled) {
            startScanner();
        } else {
            stopScanner();
        }
    }

    private void startScanner() {
        synchronized (scanLock) {
            if (scanThread != null) {
                return;
            }
            scanThread = Thread.ofVirtual().name("autostart-scan").start(this::scanLoop);
        }
        out().println("[AutoStart] Background pipe scanning started.");
    }

    private void stopScanner() {
        Thread stopped;
        synchronized (scanLock) {
            stopped = scanThread;
            scanThread = null;
        }
        if (stopped != null) {
            stopped.interrupt();
            out().println("[AutoStart] Background pipe scanning stopped.");
        }
    }

    /**
     * Called after a connection is established and account info has been probed.
     * Looks up the account's profile and auto-starts configured scripts.
     */
    public void onConnectionEstablished(Connection conn, String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return;
        }

        conn.setAccountName(displayName);

        String uuid = conn.getAccountUuid();
        if (uuid == null || uuid.isBlank()) {
            out().println("[AutoStart] No accountUuid for " + displayName
                    + " — agent reply missing the field; auto-start skipped.");
            return;
        }

        // Push the uuid into the runtime so any scripts registered against this
        // connection persist their config under the right per-account bucket.
        conn.getRuntime().setAccountUuid(uuid);

        // Wire state change callback to auto-save
        conn.getRuntime().setOnStateChange(() -> saveState(conn));

        if (!profileStore.isAutoStart(uuid)) {
            out().println("[AutoStart] Auto-start disabled for " + displayName + ".");
            return;
        }

        List<String> scriptNames = profileStore.getAccountScripts(uuid);
        if (scriptNames.isEmpty()) {
            out().println("[AutoStart] No scripts configured for " + displayName + ".");
            return;
        }

        // Load available scripts and start matching ones
        ScriptRuntime runtime = conn.getRuntime();
        List<BotScript> available = ctx.loadScripts();
        List<BotScript> blueprints = ctx.loadBlueprints();

        int started = 0;
        for (String targetName : scriptNames) {
            // Check if already registered
            ScriptRunner existing = runtime.findRunner(targetName);
            if (existing != null) {
                if (!existing.isRunning()) {
                    existing.start();
                    started++;
                }
                continue;
            }

            // Find in available scripts
            BotScript match = findScript(targetName, available);
            if (match == null) {
                match = findScript(targetName, blueprints);
            }
            if (match != null) {
                runtime.startScript(match);
                started++;
            } else {
                out().println("[AutoStart] Script not found: " + targetName);
            }
        }

        if (started > 0) {
            out().println("[AutoStart] Started " + started + " script(s) for " + displayName + ".");
        }
    }

    /**
     * Saves the current running script state for a connection's account.
     */
    public void saveState(Connection conn) {
        String uuid = conn.getAccountUuid();
        if (uuid == null || uuid.isBlank()) {
            return;
        }

        List<String> runningScripts = conn.getRuntime().getRunners().stream()
                .filter(ScriptRunner::isRunning)
                .map(ScriptRunner::getScriptName)
                .toList();

        profileStore.setAccountScripts(uuid, runningScripts);
        String displayName = conn.getAccountName();
        if (displayName != null && !displayName.isBlank()) {
            profileStore.setDisplayName(uuid, displayName);
        }
    }

    /**
     * Saves state for all active connections.
     */
    public void saveAllState() {
        for (Connection conn : ctx.getConnections()) {
            if (conn.isAlive()) {
                saveState(conn);
            }
        }
    }

    /**
     * Scans until this thread stops being {@link #scanThread}. Prefix and
     * interval are read from the settings on every pass, so a change applies
     * from the next scan without restarting the scanner.
     */
    private void scanLoop() {
        Thread self = Thread.currentThread();
        try {
            Thread.sleep(initialScanDelay);
            while (scanThread == self) {
                connectNewPipes(settings.get(SettingKeys.PIPE_PREFIX));
                reprobeUnidentifiedConnections();
                Thread.sleep(Duration.ofMillis(settings.get(SettingKeys.SCAN_INTERVAL_MS)));
            }
        } catch (InterruptedException e) {
            self.interrupt();
        }
    }

    private void connectNewPipes(String prefix) {
        List<String> pipes = pipeScanner.apply(prefix);
        for (String pipeName : pipes) {
            if (scanThread != Thread.currentThread()) {
                return;
            }
            boolean alreadyConnected = ctx.getConnections().stream()
                    .anyMatch(c -> c.getName().equals(pipeName));
            if (alreadyConnected) {
                continue;
            }

            out().println("[AutoStart] Found new pipe: " + pipeName);
            try {
                ctx.connect(pipeName);
                Connection conn = findConnectionByName(pipeName);
                if (conn != null) {
                    probeAndAutoStart(conn);
                }
            } catch (Exception e) {
                out().println("[AutoStart] Failed to connect to " + pipeName + ": " + e.getMessage());
            }
        }
    }

    /**
     * Walks every live connection and retries the account-info probe on
     * any that haven't resolved an identity yet. This catches pipes that
     * connected pre-login: the first probe at connect time returns empty,
     * we kick {@code login_to_lobby}, and subsequent ticks resolve the
     * display name once the game advances past the title screen.
     */
    private void reprobeUnidentifiedConnections() {
        for (Connection conn : ctx.getConnections()) {
            if (!conn.isAlive()) {
                continue;
            }
            if (conn.getAccountName() != null && !conn.getAccountName().isEmpty()) {
                continue;
            }
            probeAndAutoStart(conn);
        }
    }

    private void probeAndAutoStart(Connection conn) {
        try {
            Map<String, Object> info = conn.getRpc().callSync("get_account_info", Map.of());
            String displayName = getString(info, "display_name");
            if (displayName == null || displayName.isEmpty()) {
                displayName = getString(info, "jx_display_name");
            }
            if (displayName != null && !displayName.isEmpty()) {
                conn.setAccountInfo(info);
                // Load and register scripts before auto-starting
                List<BotScript> scripts = ctx.loadScripts();
                for (BotScript script : scripts) {
                    conn.getRuntime().registerScript(script);
                }
                List<BotScript> blueprints = ctx.loadBlueprints();
                for (BotScript bp : blueprints) {
                    conn.getRuntime().registerScript(bp);
                }
                onConnectionEstablished(conn, displayName);
                return;
            }
            kickLobbyLogin(conn);
        } catch (Exception e) {
            out().println("[AutoStart] Failed to probe account on " + conn.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Sends a single {@code login_to_lobby} kick per connection when the
     * account-info probe came back empty. The producer-side handler is
     * currently a stub on some builds; we log once at WARN level so the
     * user understands why the game may not advance even though the
     * Java side dispatched the RPC.
     */
    private void kickLobbyLogin(Connection conn) {
        if (conn.isLobbyLoginAttempted()) {
            return;
        }
        conn.setLobbyLoginAttempted(true);
        try {
            conn.getRpc().callSync("login_to_lobby", Map.of());
            out().println("[AutoStart] " + conn.getName()
                    + " — no account identity yet; sent login_to_lobby.");
            if (lobbyStubWarned.compareAndSet(false, true)) {
                log.warn("login_to_lobby was dispatched but the producer-side handler may"
                        + " still be a stub on this build — Java will keep re-probing for"
                        + " an identity but the game will not advance until the producer"
                        + " ships a real implementation.");
            }
        } catch (Exception e) {
            out().println("[AutoStart] login_to_lobby failed on "
                    + conn.getName() + ": " + e.getMessage());
        }
    }

    private Connection findConnectionByName(String name) {
        for (Connection c : ctx.getConnections()) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    private BotScript findScript(String name, List<BotScript> scripts) {
        for (BotScript script : scripts) {
            String scriptName = script.getClass().getSimpleName();
            var manifest = script.getClass().getAnnotation(ScriptManifest.class);
            if (manifest != null) {
                scriptName = manifest.name();
            }
            if (scriptName.equalsIgnoreCase(name)) {
                return script;
            }
        }
        return null;
    }

    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private PrintStream out() {
        return ctx.out();
    }
}
