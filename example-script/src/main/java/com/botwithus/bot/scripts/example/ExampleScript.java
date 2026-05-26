package com.botwithus.bot.scripts.example;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.event.ActionExecutedEvent;
import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.ui.ScriptUI;

import imgui.ImGui;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@ScriptManifest(
        name = "Example Script",
        version = "1.0",
        author = "BotWithUs",
        description = "A demo script showing the entity query API and Live Config",
        category = ScriptCategory.UTILITY
)
public class ExampleScript implements BotScript {

    private static final Logger log = LoggerFactory.getLogger(ExampleScript.class);

    private static final int DEFAULT_LOOP_DELAY_MS = 5000;
    private static final int PROGRESS_TARGET_LOOPS = 100;

    private ScriptContext ctx;
    private int loopCount;
    private int loopDelay = DEFAULT_LOOP_DELAY_MS;
    private boolean verbose = true;

    private GameAPI api;

    @Override
    public void onStart(ScriptContext ctx) {
        this.ctx = ctx;
        this.loopCount = 0;
        this.api = ctx.getGameAPI();

        log.info("Started!");

        EventBus events = ctx.getEventBus();
        events.subscribe(ActionExecutedEvent.class, this::handleActionEvent);
    }

    private void handleActionEvent(ActionExecutedEvent event) {
        log.debug("Action {} {} {} {}", event.actionId(), event.param1(), event.param2(), event.param3());
    }

    @Override
    public List<ConfigField> getConfigFields() {
        return List.of(
                ConfigField.intField("loopDelay", "Loop Delay (ms)", DEFAULT_LOOP_DELAY_MS),
                ConfigField.boolField("verbose", "Verbose Logging", true),
                ConfigField.choiceField("mode", "Operating Mode",
                        List.of("Passive", "Active", "Aggressive"), "Passive")
        );
    }

    @Override
    public void onConfigUpdate(ScriptConfig config) {
        this.loopDelay = config.getInt("loopDelay", DEFAULT_LOOP_DELAY_MS);
        this.verbose = config.getBoolean("verbose", true);
        String mode = config.getString("mode", "Passive");
        if (verbose) {
            log.info("Config updated: delay={}, mode={}", loopDelay, mode);
        }
    }

    @Override
    public int onLoop() {
        loopCount++;
        LocalPlayer lp = api.getLocalPlayer();
        if (lp != null && verbose) {
            log.debug("LocalPlayer at ({}, {}, plane {}) anim={}",
                    lp.tileX(), lp.tileY(), lp.plane(), lp.animationId());
        }
        return loopDelay;
    }

    @Override
    public void onStop() {
        log.info("Stopped after {} loops.", loopCount);
    }

    private final ImBoolean showEntities = new ImBoolean(false);



    private final ScriptUI ui = () -> {
        if (ImGui.collapsingHeader("Status", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.text("Loop Count: " + loopCount);
            ImGui.text("Loop Delay: " + loopDelay + "ms");

            ImGui.text("Verbose: " + verbose);
            ImGui.progressBar(
                    Math.min(loopCount / (float) PROGRESS_TARGET_LOOPS, 1f),
                    -1, 0,
                    loopCount + " / " + PROGRESS_TARGET_LOOPS + " loops");
        }

        ImGui.spacing();

        if (ImGui.collapsingHeader("Controls", ImGuiTreeNodeFlags.DefaultOpen)) {
            if (ImGui.button("Reset Counter")) {
                loopCount = 0;
            }
            ImGui.sameLine();
            if (ImGui.button("Print Stats")) {
                log.info("Stats: loops={}, delay={}", loopCount, loopDelay);
            }
        }

        ImGui.spacing();

        ImGui.checkbox("Show Entity Summary", showEntities);
        if (showEntities.get() && api != null) {
            ImGui.separator();
            int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg;
            if (ImGui.beginTable("entitySummary", 2, flags)) {
                ImGui.tableSetupColumn("Type");
                ImGui.tableSetupColumn("Count");
                ImGui.tableHeadersRow();
                addEntityRow("NPCs", api.npcs().query().all().size());
                addEntityRow("Players", api.players().all().size());
                addEntityRow("Scene Objects", api.objects().query().all().size());
                ImGui.endTable();
            }
        }
    };

    private static void addEntityRow(String label, int count) {
        ImGui.tableNextRow();
        ImGui.tableNextColumn();
        ImGui.text(label);
        ImGui.tableNextColumn();
        ImGui.text(String.valueOf(count));
    }

    @Override
    public ScriptUI getUI() {
        return ui;
    }
}
