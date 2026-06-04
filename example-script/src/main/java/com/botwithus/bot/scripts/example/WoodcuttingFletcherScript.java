package com.botwithus.bot.scripts.example;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.snapshot.LocalPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chops the nearest tree until inventory is full, then drops logs to
 * resume chopping. The original demo opened the Make-X production
 * interface to fletch arrow shafts — that path used a wider {@code GameAPI}
 * surface than the rewrite exposes, so this trimmed version sticks to
 * the entity-query and inventory-interaction primitives that remain.
 */
@ScriptManifest(
        name = "Woodcutting Fletcher",
        version = "1.0",
        author = "BotWithUs",
        description = "Chops trees and drops logs when full",
        category = ScriptCategory.WOODCUTTING
)
public class WoodcuttingFletcherScript implements BotScript {

    private static final Logger log = LoggerFactory.getLogger(WoodcuttingFletcherScript.class);

    private static final int LOGS_ITEM_ID = 1511;
    private static final int CHOPPING_DISTANCE_TILES = 15;
    private static final int LOOP_AFTER_CHOP_MS = 1200;
    private static final int LOOP_WAITING_MS = 600;
    private static final int LOOP_STATE_TRANSITION_MS = 300;
    private static final int LOOP_DROPPING_MS = 800;
    private static final int LOOP_ON_ERROR_MS = 5000;
    private static final int DROP_LOGS_OPTION_INDEX = 7;

    private enum State { CHOPPING, DROPPING }

    private GameAPI api;
    private Backpack backpack;
    private State state;

    @Override
    public void onStart(ScriptContext ctx) {
        this.api = ctx.getGameAPI();
        this.backpack = api.backpack();
        this.state = State.CHOPPING;
        log.info("Started!");
    }

    @Override
    public int onLoop() {
        try {
            log.debug("Looping {}", state);
            return switch (state) {
                case CHOPPING -> handleChopping();
                case DROPPING -> handleDropping();
            };
        } catch (RuntimeException e) {
            log.error("Error in onLoop", e);
            return LOOP_ON_ERROR_MS;
        }
    }

    private int handleChopping() {
        if (backpack.isFull()) {
            log.info("Inventory full, switching to dropping.");
            state = State.DROPPING;
            return LOOP_STATE_TRANSITION_MS;
        }

        if (isAnimating()) {
            return LOOP_WAITING_MS;
        }

        SceneObject tree = api.objects().query()
                .namedExact("Tree")
                .withinDistance(CHOPPING_DISTANCE_TILES)
                .nearest();

        if (tree == null) {
            log.warn("No tree found within {} tiles.", CHOPPING_DISTANCE_TILES);
            return LOOP_WAITING_MS;
        }

        tree.interact("Chop down");
        return LOOP_AFTER_CHOP_MS;
    }

    private int handleDropping() {
        if (!backpack.contains(LOGS_ITEM_ID)) {
            log.info("No logs remaining, switching to chopping.");
            state = State.CHOPPING;
            return LOOP_STATE_TRANSITION_MS;
        }
        backpack.interactFirst(LOGS_ITEM_ID, DROP_LOGS_OPTION_INDEX);
        return LOOP_DROPPING_MS;
    }

    private boolean isAnimating() {
        LocalPlayer lp = api.getLocalPlayer();
        return lp != null && lp.animationId() != -1;
    }

    @Override
    public void onStop() {
        log.info("Stopped.");
    }
}
