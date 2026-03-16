package com.botwithus.bot.cli.gui;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.core.runtime.ScriptProfiler;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import imgui.ImGui;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Scripts management panel — list scripts, start/stop/restart, configure, reload.
 */
public class ScriptsPanel implements GuiPanel {

    private final ExecutorService executor;
    private final ImBoolean autoStartOnReload = new ImBoolean(false);
    private final ImInt selectedConnection = new ImInt(0);

    public ScriptsPanel(ExecutorService executor) {
        this.executor = executor;
    }

    @Override
    public String title() {
        return "Scripts";
    }

    @Override
    public void render(CliContext ctx) {
        // Top controls
        if (ImGui.button("Reload Scripts")) {
            boolean startAfter = autoStartOnReload.get();
            executor.submit(() -> reloadScripts(ctx, startAfter));
        }
        ImGui.sameLine();
        ImGui.checkbox("Auto-start", autoStartOnReload);

        ImGui.sameLine(0, 20);
        boolean watcherRunning = ctx.isWatcherRunning();
        if (watcherRunning) {
            if (ImGui.button("Stop Watcher")) {
                ctx.stopScriptWatcher();
            }
            ImGui.sameLine();
            ImGui.textColored(ImGuiTheme.YELLOW_R, ImGuiTheme.YELLOW_G, ImGuiTheme.YELLOW_B, 1f, "Watching...");
        } else {
            if (ImGui.button("Start Watcher")) {
                ctx.startScriptWatcher();
            }
        }

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // Connection selector if multiple connections
        var connections = new ArrayList<>(ctx.getConnections());
        if (connections.isEmpty()) {
            ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f,
                    "No active connections. Connect first via the Connections tab.");
            return;
        }

        if (connections.size() > 1) {
            String[] connNames = connections.stream().map(Connection::getName).toArray(String[]::new);
            ImGui.text("Connection:");
            ImGui.sameLine();
            ImGui.pushItemWidth(200);
            if (selectedConnection.get() >= connNames.length) {
                selectedConnection.set(0);
            }
            ImGui.combo("##connSelector", selectedConnection, connNames);
            ImGui.popItemWidth();
            ImGui.spacing();
        } else {
            selectedConnection.set(0);
        }

        Connection conn = connections.get(selectedConnection.get());
        ScriptRuntime runtime = conn.getRuntime();
        List<ScriptRunner> runners = new ArrayList<>(runtime.getRunners());
        runners.sort(Comparator.comparing(ScriptRunner::getScriptName, String.CASE_INSENSITIVE_ORDER));

        if (runners.isEmpty()) {
            ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f,
                    "No scripts loaded on " + conn.getName() + ". Click 'Reload Scripts' to discover scripts.");
            return;
        }

        // Scripts table
        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
        if (ImGui.beginTable("scriptsTable", 7, flags)) {
            ImGui.tableSetupColumn("#", 0, 0.3f);
            ImGui.tableSetupColumn("Name", 0, 1.5f);
            ImGui.tableSetupColumn("Author", 0, 0.8f);
            ImGui.tableSetupColumn("Version", 0, 0.5f);
            ImGui.tableSetupColumn("Status", 0, 0.6f);
            ImGui.tableSetupColumn("Avg Loop", 0, 0.6f);
            ImGui.tableSetupColumn("Actions", 0, 1.5f);
            ImGui.tableHeadersRow();

            for (int i = 0; i < runners.size(); i++) {
                ScriptRunner runner = runners.get(i);
                ScriptManifest manifest = runner.getManifest();
                ScriptProfiler profiler = runner.getProfiler();

                ImGui.tableNextRow();

                ImGui.tableSetColumnIndex(0);
                ImGui.text(String.valueOf(i + 1));

                ImGui.tableSetColumnIndex(1);
                ImGui.text(runner.getScriptName());

                ImGui.tableSetColumnIndex(2);
                ImGui.text(manifest != null && !manifest.author().isEmpty() ? manifest.author() : "-");

                ImGui.tableSetColumnIndex(3);
                ImGui.text(manifest != null ? manifest.version() : "?");

                ImGui.tableSetColumnIndex(4);
                if (runner.isRunning()) {
                    ImGui.textColored(ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B, 1f, "RUNNING");
                } else {
                    ImGui.textColored(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 1f, "STOPPED");
                }

                ImGui.tableSetColumnIndex(5);
                if (profiler.getLoopCount() > 0) {
                    ImGui.text(String.format("%.1fms", profiler.avgLoopMs()));
                } else {
                    ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f, "-");
                }

                ImGui.tableSetColumnIndex(6);
                ImGui.pushID("script_actions_" + i);

                if (runner.isRunning()) {
                    if (ImGui.smallButton("Stop")) {
                        runner.stop();
                    }
                    ImGui.sameLine();
                    if (ImGui.smallButton("Restart")) {
                        executor.submit(() -> {
                            runner.stop();
                            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                            runner.start();
                        });
                    }
                } else {
                    if (ImGui.smallButton("Start")) {
                        runner.start();
                    }
                }

                // Config button — show for ConfigField-based or custom ImGui UI scripts
                var configFields = runner.getConfigFields();
                boolean hasConfig = (configFields != null && !configFields.isEmpty()) || runner.getScript().getUI() != null;
                if (hasConfig) {
                    ImGui.sameLine();
                    if (ImGui.smallButton("Config")) {
                        ctx.openConfigPanel(runner);
                    }
                }

                ImGui.popID();
            }

            ImGui.endTable();
        }
    }

    private void reloadScripts(CliContext ctx, boolean autoStart) {
        List<BotScript> scripts = ctx.loadScripts();
        List<BotScript> blueprints = ctx.loadBlueprints();

        for (Connection conn : ctx.getConnections()) {
            if (!conn.isAlive()) continue;
            ScriptRuntime runtime = conn.getRuntime();
            runtime.stopAll();
            for (BotScript script : scripts) {
                runtime.registerScript(script);
            }
            for (BotScript bp : blueprints) {
                runtime.registerScript(bp);
            }
            ctx.out().println("Reloaded " + (scripts.size() + blueprints.size()) + " script(s) on " + conn.getName());

            if (autoStart) {
                for (ScriptRunner runner : runtime.getRunners()) {
                    runner.start();
                }
                ctx.out().println("Auto-started all scripts on " + conn.getName());
            }
        }
    }
}
