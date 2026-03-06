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
}
