package com.botwithus.bot.scripts.example;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.Navigation;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.debug.ScriptContextPublisher;
import com.botwithus.bot.api.model.WalkResult;
import com.botwithus.bot.api.snapshot.LocalPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks to the world-map flag using the world pathfinder.
 *
 * <p>The destination is read live from varp {@value #WORLD_MAP_FLAG_VARP}
 * (RS3), which the client uses to store the right-clicked world-map flag
 * ("blue marker"). The varp holds a packed coordinate using the client's
 * standard {@code coord} layout — {@code (plane << 28) | (x << 14) | y} —
 * which is decoded here into a tile and handed to
 * {@link Navigation#walkWorldPath(int, int, int)}.
 *
 * <p>The varp is re-read every loop, so moving the flag in-game retargets
 * the walk. When no flag is set the varp is {@code <= 0} and the script
 * idles until one appears.
 */
@ScriptManifest(
        name = "Walk to Flag",
        version = "1.0",
        author = "BotWithUs",
        description = "Walks to the world-map flag (varp 2807) using world pathfinding",
        category = ScriptCategory.UTILITY
)
public class WalkToFlagScript implements BotScript {

    private static final Logger log = LoggerFactory.getLogger(WalkToFlagScript.class);

    private static final int LOOP_AFTER_WALK_MS = 1000;

    /** Varp holding the world-map flag as a packed {@code coord}. */
    private static final int WORLD_MAP_FLAG_VARP = 2807;

    // Packed-coord layout shared with the client's CS2 "coord"/Location type:
    //   hash = (plane << COORD_PLANE_SHIFT) | (x << COORD_X_SHIFT) | y
    private static final int COORD_PLANE_SHIFT = 28;
    private static final int COORD_X_SHIFT = 14;
    private static final int COORD_COMPONENT_MASK = 0x3FFF;
    private static final int COORD_PLANE_MASK = 0x3;

    private ScriptContext ctx;
    private int targetX = -1;
    private int targetY = -1;
    private int targetPlane = -1;

    @Override
    public void onStart(ScriptContext ctx) {
        this.ctx = ctx;
        log.info("Walk to Flag script started — following world-map flag (varp {}).",
                WORLD_MAP_FLAG_VARP);
    }

    @Override
    public int onLoop() {
        ScriptContextPublisher publisher = ctx.getScriptContext();
        LocalPlayer lp = ctx.getGameAPI().getLocalPlayer();
        if (lp != null) {
            publisher.annotation("position", lp.tileX() + "," + lp.tileY() + ",p" + lp.plane());
        }

        int packed = ctx.getGameAPI().getVarp(WORLD_MAP_FLAG_VARP);
        if (packed <= 0) {
            publisher.annotation("destination", "none");
            publisher.trace("INFO", "No world-map flag set (varp " + WORLD_MAP_FLAG_VARP + ")");
            return LOOP_AFTER_WALK_MS;
        }
        decodeFlag(packed);
        publishDestination();

        if (lp != null && lp.tileX() == targetX && lp.tileY() == targetY && lp.plane() == targetPlane) {
            log.info("At world-map flag ({}, {}, plane {}) — idling.", targetX, targetY, targetPlane);
            publisher.trace("INFO", "Arrived at world-map flag");
            return LOOP_AFTER_WALK_MS;
        }

        log.info("Walking to world-map flag ({}, {}, plane {})...", targetX, targetY, targetPlane);
        publisher.trace("INFO",
                "Walking to flag (" + targetX + "," + targetY + ",p" + targetPlane + ")");
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

    /** Unpacks the world-map flag {@code coord} into the target tile fields. */
    private void decodeFlag(int packed) {
        this.targetPlane = (packed >> COORD_PLANE_SHIFT) & COORD_PLANE_MASK;
        this.targetX = (packed >> COORD_X_SHIFT) & COORD_COMPONENT_MASK;
        this.targetY = packed & COORD_COMPONENT_MASK;
    }

    private void publishDestination() {
        ctx.getScriptContext().annotation("destination",
                targetX + "," + targetY + ",p" + targetPlane);
    }

    @Override
    public void onStop() {
        log.info("Walk to Flag script stopped.");
    }
}
