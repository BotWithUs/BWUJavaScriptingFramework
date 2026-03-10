package com.botwithus.bot.scripts.example;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.entities.*;
import com.botwithus.bot.api.event.ActionExecutedEvent;
import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.ui.ScriptUI;

import imgui.ImGui;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImBoolean;

import java.util.List;

@ScriptManifest(
        name = "Example Script",
        version = "1.0",
        author = "BotWithUs",
        description = "A demo script showing the entity query API"
)
public class ExampleScript implements BotScript {

    private ScriptContext ctx;
    private int loopCount;
    private int loopDelay = 5000;
    private boolean verbose = true;

    // Scripter-friendly query facades — initialize once in onStart
    private Npcs npcs;
    private SceneObjects objects;
    private Players players;
    private GroundItems groundItems;

    @Override
    public void onStart(ScriptContext ctx) {
        this.ctx = ctx;
        this.loopCount = 0;

        GameAPI api = ctx.getGameAPI();
        this.npcs = new Npcs(api);
        this.objects = new SceneObjects(api);
        this.players = new Players(api);
        this.groundItems = new GroundItems(api);

        System.out.println("[ExampleScript] Started!");

        EventBus events = ctx.getEventBus();
        events.subscribe(ActionExecutedEvent.class, this::handleActionEvent);
    }

    private void handleActionEvent(ActionExecutedEvent event) {
        System.out.println("Action " + event.getActionId() + " " + event.getParam1() + " " + event.getParam2() + " " + event.getParam3());
    }

    @Override
    public List<ConfigField> getConfigFields() {
        return List.of(
                ConfigField.intField("loopDelay", "Loop Delay (ms)", 5000),
                ConfigField.boolField("verbose", "Verbose Logging", true),
                ConfigField.choiceField("mode", "Operating Mode",
                        List.of("Passive", "Active", "Aggressive"), "Passive")
        );
    }

    @Override
    public void onConfigUpdate(ScriptConfig config) {
        this.loopDelay = config.getInt("loopDelay", 5000);
        this.verbose = config.getBoolean("verbose", true);
        String mode = config.getString("mode", "Passive");
        if (verbose) {
            System.out.println("[ExampleScript] Config updated: delay=" + loopDelay + ", mode=" + mode);
        }
    }

    @Override
    public int onLoop() {
        loopCount++;
        return loopDelay;
    }

    @Override
    public void onStop() {
        System.out.println("[ExampleScript] Stopped after " + loopCount + " loops.");
    }

    // ── Custom Script UI ──────────────────────────────────────────────────────

    private final ImBoolean showEntities = new ImBoolean(false);

    private final ScriptUI ui = () -> {
        if (ImGui.collapsingHeader("Status", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.text("Loop Count: " + loopCount);
            ImGui.text("Loop Delay: " + loopDelay + "ms");
            ImGui.text("Verbose: " + verbose);
            ImGui.progressBar(Math.min(loopCount / 100f, 1f), -1, 0,
                    loopCount + " / 100 loops");
        }

        ImGui.spacing();

        if (ImGui.collapsingHeader("Controls", ImGuiTreeNodeFlags.DefaultOpen)) {
            if (ImGui.button("Reset Counter")) {
                loopCount = 0;
            }
            ImGui.sameLine();
            if (ImGui.button("Print Stats")) {
                System.out.println("[ExampleScript] Stats: loops=" + loopCount
                        + ", delay=" + loopDelay);
            }
        }

        ImGui.spacing();

        ImGui.checkbox("Show Entity Summary", showEntities);
        if (showEntities.get() && ctx != null) {
            ImGui.separator();
            int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg;
            if (ImGui.beginTable("entitySummary", 2, flags)) {
                ImGui.tableSetupColumn("Type");
                ImGui.tableSetupColumn("Count");
                ImGui.tableHeadersRow();

                ImGui.tableNextRow();
                ImGui.tableNextColumn(); ImGui.text("NPCs");
                ImGui.tableNextColumn(); ImGui.text(String.valueOf(
                        npcs != null ? npcs.query().all().size() : 0));

                ImGui.tableNextRow();
                ImGui.tableNextColumn(); ImGui.text("Players");
                ImGui.tableNextColumn(); ImGui.text(String.valueOf(
                        players != null ? players.query().all().size() : 0));

                ImGui.tableNextRow();
                ImGui.tableNextColumn(); ImGui.text("Scene Objects");
                ImGui.tableNextColumn(); ImGui.text(String.valueOf(
                        objects != null ? objects.query().all().size() : 0));

                ImGui.endTable();
            }
        }
    };

    @Override
    public ScriptUI getUI() {
        return ui;
    }
}
