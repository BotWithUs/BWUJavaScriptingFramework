package com.botwithus.bot.scripts.example;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.entities.SceneObjects;
import com.botwithus.bot.api.event.ActionExecutedEvent;
import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.model.GameAction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chops trees until inventory is full, then fletches logs into arrow shafts.
 * Repeats indefinitely.
 */
@ScriptManifest(
        name = "Woodcutting Fletcher",
        version = "1.0",
        author = "BotWithUs",
        description = "Chops trees and fletches logs into arrow shafts",
        category = ScriptCategory.WOODCUTTING
)
public class WoodcuttingFletcherScript implements BotScript {

    private static final Logger log = LoggerFactory.getLogger(WoodcuttingFletcherScript.class);

    private static final int LOGS = 1511;
    /** RS3 production / Make-X interface. */
    private static final int PRODUCTION_INTERFACE = 1370;
    /** Arrow shaft button component within the production interface. */
    private static final int ARROW_SHAFT_COMPONENT = 14;

    private enum State { CHOPPING, FLETCHING }

    private ScriptContext ctx;
    private SceneObjects objects;
    private Backpack backpack;
    private State state;

    @Override
    public void onStart(ScriptContext ctx) {
        this.ctx = ctx;
        GameAPI api = ctx.getGameAPI();
        this.objects = new SceneObjects(api);
        this.backpack = new Backpack(api);
        this.state = State.CHOPPING;
        log.info("Started!");
    }

    @Override
    public int onLoop() {
        try {
            GameAPI api = ctx.getGameAPI();

            log.debug("Looping {}", state);

            return switch (state) {
                case CHOPPING -> handleChopping(api);
                case FLETCHING -> handleFletching(api);
            };
        } catch (Exception e) {
            log.error("Error in onLoop", e);
            return 5000;
        }
    }

    private int handleChopping(GameAPI api) {
        if (backpack.isFull()) {
            log.info("Inventory full, switching to fletching.");
            state = State.FLETCHING;
            return 300;
        }

        // If player is already chopping, wait
        if (isAnimating(api)) {
            return 600;
        }

        SceneObject tree = objects.query()
                .namedExact("Tree")
                .visible()
                .withinDistance(15)
                .filter(t -> t.hasOption("Chop down"))
                .nearest();

        if (tree == null) {
            log.warn("No tree found!");
        } else {
            tree.interact("Chop down");
            return 1200;
        }

        return 600;
    }

    private int handleFletching(GameAPI api) {
        if (!backpack.contains(LOGS)) {
            log.info("No logs remaining, switching to chopping.");
            state = State.CHOPPING;
            return 300;
        }

        // If the Make-X production interface is open, click the arrow shaft option
        if (api.isInterfaceOpen(PRODUCTION_INTERFACE)) {
            if (api.isComponentValid(PRODUCTION_INTERFACE, ARROW_SHAFT_COMPONENT, -1)) {
                int hash = PRODUCTION_INTERFACE << 16 | ARROW_SHAFT_COMPONENT;
                api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, -1, hash));
            }
            return 2400; // wait for fletching to process
        }

        // If player is already fletching, wait
        if (isAnimating(api)) {
            return 600;
        }

        // Click "Fletch" on logs in the backpack to open the production interface
        backpack.interact(LOGS, "Fletch");
        return 1200;
    }

    private boolean isAnimating(GameAPI api) {
        var lp = api.getLocalPlayer();
        return lp != null && lp.animationId() != -1;
    }

    @Override
    public void onStop() {
        log.info("Stopped.");
    }
}
