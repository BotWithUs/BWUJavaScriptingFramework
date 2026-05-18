package com.botwithus.bot.scripts.example;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.entities.Npc;
import com.botwithus.bot.api.entities.Npcs;
import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.entities.SceneObjects;
import com.botwithus.bot.api.entities.WorldMapElements;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.model.PlayerStat;
import com.botwithus.bot.api.model.WorldMapElement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generic Divination script that finds the best divination spot for the
 * player's current level, walks there, harvests wisps/springs, and converts
 * memories at the rift. Recomputes the target spot on level-up.
 */
@ScriptManifest(
        name = "Divination",
        version = "1.0",
        author = "BotWithUs",
        description = "Harvests wisps and converts memories at the nearest divination spot",
        category = ScriptCategory.DIVINATION
)
public class DivinationScript implements BotScript {

    private static final Logger log = LoggerFactory.getLogger(DivinationScript.class);

    private static final int DIVINATION_CATEGORY = 3032;
    private static final int DIVINATION_SKILL_ID = 26;

    private ScriptContext ctx;
    private Npcs npcs;
    private SceneObjects objects;
    private Backpack backpack;
    private WorldMapElements mapElements;
    private WorldMapElement spot;
    private int lastLevel;

    @Override
    public void onStart(ScriptContext ctx) {
        this.ctx = ctx;
        GameAPI api = ctx.getGameAPI();
        this.npcs = new Npcs(api);
        this.objects = new SceneObjects(api);
        this.backpack = new Backpack(api);
        this.mapElements = new WorldMapElements(api);
        this.lastLevel = getDivinationLevel(api);

        updateSpot(api);
    }

    @Override
    public int onLoop() {
        GameAPI api = ctx.getGameAPI();

        // Recompute spot on level-up
        int currentLevel = getDivinationLevel(api);
        if (currentLevel > lastLevel) {
            log.info("Divination level up! {} -> {}", lastLevel, currentLevel);
            lastLevel = currentLevel;
            updateSpot(api);
        }

        if (spot == null) return -1;

        // Walk to spot if too far
        if (distanceToSpot(api) > 30) {
            log.info("Walking to divination spot...");
            ctx.getNavigation().walkWorldPath(spot.tileX(), spot.tileY());
            return 600;
        }

        // Convert memories at rift if backpack is full
        if (backpack.isFull()) {
            return convertMemories();
        }

        // Harvest if idle
        if (!isAnimating(api)) {
            return harvest();
        }

        return 600;
    }

    private void updateSpot(GameAPI api) {
        int level = getDivinationLevel(api);

        spot = mapElements.query()
                .withCategory(DIVINATION_CATEGORY)
                .withSkill(DIVINATION_SKILL_ID, 1, level)
                .withResources()
                .nearPlayer()
                .sortByDistance()
                .nearest();

        if (spot == null) {
            log.warn("No divination spot found for level {}!", level);
        } else {
            log.info("Level {} — target: {} at ({}, {})", level, spot.description(), spot.tileX(), spot.tileY());
            spot.resources().forEach(s ->
                    log.info("  {} (items: {})", s.title(),
                            s.items().stream().map(i -> i.itemId() + " @lvl" + i.level()).toList()));
        }
    }

    private int harvest() {
        // Prefer springs (stationary, better) over wisps (wandering)
        Npc target = npcs.query()
                .named("spring")
                .withinDistance(30)
                .filter(n -> n.hasOption("Harvest"))
                .nearest();

        if (target == null) {
            target = npcs.query()
                    .named("wisp")
                    .withinDistance(30)
                    .filter(n -> n.hasOption("Harvest"))
                    .nearest();
        }

        if (target != null) {
            target.interact("Harvest");
            return 1200;
        }

        log.debug("No wisp or spring found nearby");
        return 600;
    }

    private int convertMemories() {
        SceneObject rift = objects.query()
                .named("Rift")
                .withinDistance(30)
                .filter(o -> o.hasOption("Convert"))
                .nearest();

        if (rift != null) {
            rift.interact("Convert");
            return 2400;
        }

        log.debug("No rift found nearby");
        return 600;
    }

    private int getDivinationLevel(GameAPI api) {
        PlayerStat stat = api.getPlayerStat(DIVINATION_SKILL_ID);
        return stat != null ? stat.level() : 1;
    }

    private int distanceToSpot(GameAPI api) {
        var lp = api.getLocalPlayer();
        int dx = lp.tileX() - spot.tileX();
        int dy = lp.tileY() - spot.tileY();
        return (int) Math.sqrt(dx * dx + dy * dy);
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
