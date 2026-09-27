package com.botwithus.bot.cli.scripts;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.script.ManagementScript;
import com.botwithus.bot.core.runtime.ManagementScriptRunner;
import com.botwithus.bot.core.runtime.ManagementScriptRuntime;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The one reload path for script JARs: the {@code reload} command, the Reload
 * buttons and the folder watcher all go through it.
 *
 * <p>A reload of clients runs in phases. It first notes which scripts each
 * client is running, then stops every client, then loads a fresh set of
 * scripts per client and registers it, and only then starts what
 * {@link AfterReload} asks for. Stopping every client before the first load
 * matters: a load closes the class loaders of the previous one, so no script
 * may still be running when it happens.</p>
 *
 * <p>Every client gets its own instances: a script object holds per-run
 * state, so one instance registered on two clients would share it.</p>
 */
public final class ScriptReloader {

    private final Supplier<List<BotScript>> loadScripts;
    private final Supplier<List<ManagementScript>> loadManagementScripts;

    /**
     * @param loadScripts           one load pass over {@code scripts/}; called once per client
     * @param loadManagementScripts one load pass over {@code scripts/management/}
     */
    public ScriptReloader(Supplier<List<BotScript>> loadScripts,
                          Supplier<List<ManagementScript>> loadManagementScripts) {
        this.loadScripts = loadScripts;
        this.loadManagementScripts = loadManagementScripts;
    }

    /**
     * Reloads {@code targets}. With no targets it still runs one load pass,
     * so the failed-load list and the catalogue reflect the folder.
     */
    public ReloadSummary reload(List<ReloadTarget> targets, AfterReload after) {
        List<RunningPair> wasRunning = runningOn(targets);
        targets.forEach(t -> t.runtime().stopAll());
        if (targets.isEmpty()) {
            loadScripts.get();
            return new ReloadSummary(after, List.of(), List.of(), List.of());
        }
        List<ReloadSummary.ClientReload> clients = new ArrayList<>();
        Map<String, Map<String, ScriptRunner>> registered = new HashMap<>();
        for (ReloadTarget target : targets) {
            List<BotScript> scripts = loadScripts.get();
            clients.add(new ReloadSummary.ClientReload(target.connection(), scripts.size()));
            registered.put(target.connection(), install(target.runtime(), scripts, after));
        }
        if (after != AfterReload.RESTART_RUNNING) {
            return new ReloadSummary(after, clients, List.of(), List.of());
        }
        return restart(after, clients, wasRunning, registered);
    }

    /** Reloads the management runtime, restarting as {@code after} says. */
    public ManagementReload reloadManagement(ManagementScriptRuntime runtime, AfterReload after) {
        List<String> wasRunning = runtime.getRunners().stream()
                .filter(ManagementScriptRunner::isRunning)
                .map(ManagementScriptRunner::getScriptName)
                .toList();
        runtime.stopAll();
        List<ManagementScript> scripts = loadManagementScripts.get();
        Map<String, ManagementScriptRunner> byName = new LinkedHashMap<>();
        for (ManagementScript script : scripts) {
            ManagementScriptRunner runner = runtime.registerScript(script);
            byName.putIfAbsent(key(runner.getScriptName()), runner);
        }
        List<String> toStart = switch (after) {
            case REGISTER_ONLY -> List.of();
            case RESTART_RUNNING -> wasRunning;
            case START_ALL -> byName.values().stream().map(ManagementScriptRunner::getScriptName).toList();
        };
        List<String> started = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String name : toStart) {
            ManagementScriptRunner runner = byName.get(key(name));
            if (runner == null) {
                missing.add(name);
            } else {
                runner.start();
                started.add(name);
            }
        }
        List<String> restarted = after == AfterReload.RESTART_RUNNING ? started : List.of();
        return new ManagementReload(after, scripts.size(), restarted, missing);
    }

    private static List<RunningPair> runningOn(List<ReloadTarget> targets) {
        List<RunningPair> running = new ArrayList<>();
        for (ReloadTarget target : targets) {
            for (ScriptRunner runner : target.runtime().getRunners()) {
                if (runner.isRunning()) {
                    running.add(new RunningPair(target.connection(), runner.getScriptName()));
                }
            }
        }
        return running;
    }

    /** Registers {@code scripts} on {@code runtime}, starting them all for {@link AfterReload#START_ALL}. */
    private static Map<String, ScriptRunner> install(ScriptRuntime runtime, List<BotScript> scripts,
                                                     AfterReload after) {
        if (after == AfterReload.START_ALL) {
            runtime.startAll(scripts);
            return Map.of();
        }
        Map<String, ScriptRunner> byName = new HashMap<>();
        for (BotScript script : scripts) {
            ScriptRunner runner = runtime.registerScript(script);
            if (runner != null) {
                byName.putIfAbsent(key(runner.getScriptName()), runner);
            }
        }
        return byName;
    }

    private static ReloadSummary restart(AfterReload after, List<ReloadSummary.ClientReload> clients,
                                         List<RunningPair> wasRunning,
                                         Map<String, Map<String, ScriptRunner>> registered) {
        List<RunningPair> restarted = new ArrayList<>();
        List<RunningPair> missing = new ArrayList<>();
        for (RunningPair pair : wasRunning) {
            ScriptRunner runner = registered.getOrDefault(pair.connection(), Map.of()).get(key(pair.script()));
            if (runner == null) {
                missing.add(pair);
            } else {
                runner.start();
                restarted.add(pair);
            }
        }
        return new ReloadSummary(after, clients, restarted, missing);
    }

    /** Runtimes match script names case-insensitively; so does the restart. */
    private static String key(String scriptName) {
        return scriptName.toLowerCase(Locale.ROOT);
    }
}
