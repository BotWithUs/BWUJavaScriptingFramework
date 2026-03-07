package com.botwithus.bot.cli;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.core.config.ScriptProfileStore;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.runtime.ScriptRunner;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates pipe detection, account identification, and script auto-starting.
 * When enabled, scans for pipes on startup, probes account info, and starts
 * the scripts that were previously running for each account.
 */
public class AutoStartManager {

    private final CliContext ctx;
    private final ScriptProfileStore profileStore;
    private volatile boolean running;
    private Thread scanThread;

    public AutoStartManager(CliContext ctx, ScriptProfileStore profileStore) {
        this.ctx = ctx;
        this.profileStore = profileStore;
    }

    /**
     * Begins background pipe scanning if auto-connect is enabled.
     */
    public void start() {
        if (running) return;
        if (!profileStore.isAutoConnect()) return;
        running = true;
        scanThread = Thread.ofVirtual().name("autostart-scan").start(this::scanLoop);
        out().println("[AutoStart] Background pipe scanning started.");
    }

    public void stop() {
        running = false;
        if (scanThread != null) {
            scanThread.interrupt();
            scanThread = null;
        }
    }

    /**
     * Called after a connection is established and account info has been probed.
     * Looks up the account's profile and auto-starts configured scripts.
     */
    public void onConnectionEstablished(Connection conn, String displayName) {
        if (displayName == null || displayName.isBlank()) return;

        conn.setAccountName(displayName);

        // Wire state change callback to auto-save
        conn.getRuntime().setOnStateChange(() -> saveState(conn));

        if (!profileStore.isAutoStart(displayName)) {
            out().println("[AutoStart] Auto-start disabled for " + displayName + ".");
            return;
        }

        List<String> scriptNames = profileStore.getAccountScripts(displayName);
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
        String accountName = conn.getAccountName();
        if (accountName == null || accountName.isBlank()) return;

        List<String> runningScripts = conn.getRuntime().getRunners().stream()
                .filter(ScriptRunner::isRunning)
                .map(ScriptRunner::getScriptName)
                .toList();

        profileStore.setAccountScripts(accountName, runningScripts);
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

    private void scanLoop() {
        String prefix = profileStore.getPipePrefix();
        long interval = profileStore.getScanIntervalMs();

        // Initial delay to let the app finish starting up
        try { Thread.sleep(1000); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        while (running) {
            try {
                List<String> pipes = PipeClient.scanPipes(prefix);
                for (String pipeName : pipes) {
                    // Skip if already connected
                    boolean alreadyConnected = ctx.getConnections().stream()
                            .anyMatch(c -> c.getName().equals(pipeName));
                    if (alreadyConnected) continue;

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

                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
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
            }
        } catch (Exception e) {
            out().println("[AutoStart] Failed to probe account on " + conn.getName() + ": " + e.getMessage());
        }
    }

    private Connection findConnectionByName(String name) {
        for (Connection c : ctx.getConnections()) {
            if (c.getName().equals(name)) return c;
        }
        return null;
    }

    private BotScript findScript(String name, List<BotScript> scripts) {
        for (BotScript script : scripts) {
            String scriptName = script.getClass().getSimpleName();
            var manifest = script.getClass().getAnnotation(com.botwithus.bot.api.ScriptManifest.class);
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
