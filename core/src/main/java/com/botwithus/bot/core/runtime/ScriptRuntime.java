package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages multiple ScriptRunners and their lifecycles.
 */
public class ScriptRuntime {

    private final ScriptContext context;
    private final List<ScriptRunner> runners = new CopyOnWriteArrayList<>();
    private String connectionName;
    private Runnable onStateChange;

    public ScriptRuntime(ScriptContext context) {
        this.context = context;
    }

    public void setConnectionName(String connectionName) {
        this.connectionName = connectionName;
    }

    public String getConnectionName() {
        return connectionName;
    }

    public void setOnStateChange(Runnable callback) {
        this.onStateChange = callback;
    }

    private void fireStateChange() {
        Runnable cb = this.onStateChange;
        if (cb != null) {
            try {
                cb.run();
            } catch (Exception e) {
                System.err.println("[Runtime] State change callback error: " + e.getMessage());
            }
        }
    }

    /**
     * Registers a script without starting it. Use {@link ScriptRunner#start()} to start later.
     */
    public ScriptRunner registerScript(BotScript script) {
        ScriptRunner runner = new ScriptRunner(script, context);
        if (connectionName != null) {
            runner.setConnectionName(connectionName);
        }
        runners.add(runner);
        return runner;
    }

    public void startScript(BotScript script) {
        ScriptRunner runner = registerScript(script);
        runner.start();
        System.out.println("[Runtime] Started script: " + runner.getScriptName());
        fireStateChange();
    }

    public void startAll(List<BotScript> scripts) {
        for (BotScript script : scripts) {
            startScript(script);
        }
    }

    public void stopAll() {
        for (ScriptRunner runner : runners) {
            runner.stop();
            System.out.println("[Runtime] Stopped script: " + runner.getScriptName());
        }
        runners.clear();
        fireStateChange();
    }

    public ScriptRunner findRunner(String name) {
        for (ScriptRunner runner : runners) {
            if (runner.getScriptName().equalsIgnoreCase(name)) {
                return runner;
            }
        }
        return null;
    }

    public boolean stopScript(String name) {
        ScriptRunner runner = findRunner(name);
        if (runner != null && runner.isRunning()) {
            runner.stop();
            System.out.println("[Runtime] Stopped script: " + runner.getScriptName());
            fireStateChange();
            return true;
        }
        return false;
    }

    public boolean removeScript(String name) {
        ScriptRunner runner = findRunner(name);
        if (runner != null && !runner.isRunning()) {
            runners.remove(runner);
            return true;
        }
        return false;
    }

    public List<ScriptRunner> getRunners() {
        return List.copyOf(runners);
    }
}
