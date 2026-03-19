package com.botwithus.bot.cli.gui;

import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.script.ManagementScript;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.core.runtime.ManagementScriptRunner;
import com.botwithus.bot.core.runtime.ManagementScriptRuntime;

import imgui.ImGui;
import imgui.flag.ImGuiTableFlags;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Management scripts panel — load, start/stop/restart, and configure management scripts.
 * Management scripts run independently of connections and coordinate across clients.
 */
public class ManagementScriptsPanel implements GuiPanel {

    private final ExecutorService executor;
    private Consumer<ManagementScriptRunner> configOpener;

    public ManagementScriptsPanel(ExecutorService executor) {
        this.executor = executor;
    }

    public void setConfigOpener(Consumer<ManagementScriptRunner> opener) {
        this.configOpener = opener;
    }

    @Override
    public String title() {
        return "Management";
    }

    @Override
    public void render(CliContext ctx) {
        ManagementScriptRuntime runtime = ctx.getManagementRuntime();
        if (runtime == null) {
            ImGui.textColored(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 1f,
                    "Management runtime not initialized.");
            return;
        }

        // Top controls
        if (GuiHelpers.buttonPrimary(Icons.DOWNLOAD + "  Load Scripts")) {
            executor.submit(() -> loadManagementScripts(ctx));
        }
        ImGui.sameLine(0, 8);
        if (GuiHelpers.buttonSecondary(Icons.ROTATE + "  Reload")) {
            executor.submit(() -> reloadManagementScripts(ctx));
        }
        ImGui.sameLine(0, 8);
        if (GuiHelpers.buttonDanger(Icons.STOP + "  Stop All")) {
            runtime.stopAll();
            ctx.out().println("Stopped all management scripts.");
        }

        ImGui.sameLine(0, 20);
        GuiHelpers.textMuted("scripts/management/");

        GuiHelpers.sectionHeader("Management Scripts");

        List<ManagementScriptRunner> runners = new ArrayList<>(runtime.getRunners());
        runners.sort(Comparator.comparing(ManagementScriptRunner::getScriptName, String.CASE_INSENSITIVE_ORDER));

        if (runners.isEmpty()) {
            ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f,
                    "No management scripts loaded. Click 'Load Scripts' to discover scripts from scripts/management/.");
            return;
        }

        // Scripts table
        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
        if (ImGui.beginTable("mgmtScriptsTable", 6, flags)) {
            ImGui.tableSetupColumn("#", 0, 0.3f);
            ImGui.tableSetupColumn("Name", 0, 1.5f);
            ImGui.tableSetupColumn("Author", 0, 0.8f);
            ImGui.tableSetupColumn("Version", 0, 0.5f);
            ImGui.tableSetupColumn("Status", 0, 0.6f);
            ImGui.tableSetupColumn("Actions", 0, 1.8f);
            ImGui.tableHeadersRow();

            for (int i = 0; i < runners.size(); i++) {
                ManagementScriptRunner runner = runners.get(i);
                ScriptManifest manifest = runner.getManifest();

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
                ImGui.pushID("mgmt_actions_" + i);

                if (runner.isRunning()) {
                    if (GuiHelpers.smallButtonDanger(Icons.STOP + " Stop")) {
                        runner.stop();
                    }
                    ImGui.sameLine();
                    if (ImGui.smallButton(Icons.REDO + " Restart")) {
                        executor.submit(() -> {
                            runner.stop();
                            runner.awaitStop(2000);
                            runner.start();
                        });
                    }
                } else {
                    if (ImGui.smallButton(Icons.PLAY + " Start")) {
                        runner.start();
                    }
                }

                // Config button
                var configFields = runner.getConfigFields();
                boolean hasConfig = (configFields != null && !configFields.isEmpty())
                        || runner.getScript().getUI() != null;
                if (hasConfig) {
                    ImGui.sameLine();
                    if (ImGui.smallButton(Icons.SLIDERS + " Config")) {
                        if (configOpener != null) {
                            configOpener.accept(runner);
                        }
                    }
                }

                // Info tooltip on hover
                ImGui.sameLine();
                if (ImGui.smallButton("Info")) {
                    // Toggle info display handled inline below
                }
                if (ImGui.isItemHovered() && manifest != null) {
                    ImGui.beginTooltip();
                    ImGui.text("Name: " + runner.getScriptName());
                    ImGui.text("Version: " + manifest.version());
                    if (!manifest.author().isEmpty()) ImGui.text("Author: " + manifest.author());
                    if (!manifest.description().isEmpty()) ImGui.text("Description: " + manifest.description());
                    ImGui.text("Class: " + runner.getScript().getClass().getName());
                    ImGui.endTooltip();
                }

                ImGui.popID();
            }

            ImGui.endTable();
        }
    }

    private void loadManagementScripts(CliContext ctx) {
        ManagementScriptRuntime runtime = ctx.getManagementRuntime();
        List<ManagementScript> scripts = ctx.loadManagementScripts();
        if (scripts.isEmpty()) {
            ctx.out().println("No management scripts found in scripts/management/.");
            return;
        }
        for (ManagementScript script : scripts) {
            runtime.registerScript(script);
        }
        ctx.out().println("Loaded " + scripts.size() + " management script(s).");
    }

    private void reloadManagementScripts(CliContext ctx) {
        ManagementScriptRuntime runtime = ctx.getManagementRuntime();
        runtime.stopAll();
        List<ManagementScript> scripts = ctx.loadManagementScripts();
        if (scripts.isEmpty()) {
            ctx.out().println("No management scripts found in scripts/management/.");
            return;
        }
        for (ManagementScript script : scripts) {
            runtime.registerScript(script);
        }
        ctx.out().println("Reloaded " + scripts.size() + " management script(s).");
    }
}
