package com.botwithus.bot.scripts.example;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.Navigation;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.debug.ScriptContextPublisher;
import com.botwithus.bot.api.model.WalkResult;
import com.botwithus.bot.api.snapshot.LocalPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Walks to a configurable destination tile using the world pathfinder.
 *
 * <p>The pre-rewrite version of this demo read the varp the client uses
 * to store the right-clicked world-map flag (varp 2807 on RS3). The
 * rewritten {@code GameAPI} does not expose varp reads, so this demo
 * was simplified to take its destination from {@link ConfigField}s
 * editable in the Script Config panel. Set X / Y / plane and the
 * script walks there using {@link Navigation#walkWorldPath(int, int, int)}.
 */
@ScriptManifest(
        name = "Walk to Flag",
        version = "1.0",
        author = "BotWithUs",
        description = "Walks to a configurable destination tile using world pathfinding",
        category = ScriptCategory.UTILITY
)
public class WalkToFlagScript implements BotScript {

    private static final Logger log = LoggerFactory.getLogger(WalkToFlagScript.class);

    private static final int LOOP_AFTER_WALK_MS = 1000;
    private static final int DEFAULT_TARGET_X = 3222;
    private static final int DEFAULT_TARGET_Y = 3219;
    private static final int DEFAULT_TARGET_PLANE = 0;

    private ScriptContext ctx;
    private int targetX = DEFAULT_TARGET_X;
    private int targetY = DEFAULT_TARGET_Y;
    private int targetPlane = DEFAULT_TARGET_PLANE;

    @Override
    public void onStart(ScriptContext ctx) {
        this.ctx = ctx;
        publishDestination();
        log.info("Walk to Flag script started — destination ({}, {}, plane {}).",
                targetX, targetY, targetPlane);
    }

    private void publishDestination() {
        if (ctx == null) {
            return;
        }
        ctx.getScriptContext().annotation("destination",
                targetX + "," + targetY + ",p" + targetPlane);
    }

    @Override
    public List<ConfigField> getConfigFields() {
        return List.of(
                ConfigField.intField("targetX", "Target X", DEFAULT_TARGET_X),
                ConfigField.intField("targetY", "Target Y", DEFAULT_TARGET_Y),
                ConfigField.intField("targetPlane", "Target Plane", DEFAULT_TARGET_PLANE));
    }

    @Override
    public void onConfigUpdate(ScriptConfig config) {
        this.targetX = config.getInt("targetX", DEFAULT_TARGET_X);
        this.targetY = config.getInt("targetY", DEFAULT_TARGET_Y);
        this.targetPlane = config.getInt("targetPlane", DEFAULT_TARGET_PLANE);
        publishDestination();
    }

    @Override
    public int onLoop() {
        ScriptContextPublisher publisher = ctx.getScriptContext();
        LocalPlayer lp = ctx.getGameAPI().getLocalPlayer();
        if (lp != null) {
            publisher.annotation("position", lp.tileX() + "," + lp.tileY() + ",p" + lp.plane());
        }
        if (lp != null && lp.tileX() == targetX && lp.tileY() == targetY && lp.plane() == targetPlane) {
            log.info("Already at destination — stopping.");
            publisher.trace("INFO", "Arrived at destination — stopping");
            return -1;
        }

        log.info("Walking to ({}, {}, plane {})...", targetX, targetY, targetPlane);
        publisher.trace("INFO",
                "Walking to (" + targetX + "," + targetY + ",p" + targetPlane + ")");
        Navigation nav = ctx.getNavigation();
        WalkResult result = nav.walkWorldPath(targetX, targetY, targetPlane);

        switch (result) {
            case ARRIVED   -> { log.info("Arrived at ({}, {})", targetX, targetY);
                                publisher.trace("INFO", "Arrived at (" + targetX + "," + targetY + ")"); }
            case CANCELLED -> { log.warn("Walk cancelled before reaching ({}, {})", targetX, targetY);
                                publisher.trace("WARN", "Walk cancelled"); }
            case FAILED    -> { log.warn("Walk failed to reach ({}, {})", targetX, targetY);
                                publisher.trace("ERROR", "Walk failed"); }
            case TIMEOUT   -> { log.warn("Walk timed out heading to ({}, {})", targetX, targetY);
                                publisher.trace("WARN", "Walk timed out"); }
        }
        publisher.annotation("last_walk_result", result.name());
        return LOOP_AFTER_WALK_MS;
    }

    @Override
    public void onStop() {
        log.info("Walk to Flag script stopped.");
    }
}
