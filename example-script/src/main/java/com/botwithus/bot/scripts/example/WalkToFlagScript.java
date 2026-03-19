package com.botwithus.bot.scripts.example;

import com.botwithus.bot.api.*;
import com.botwithus.bot.api.model.WalkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks to the blue world-map flag (set by right-clicking the world map).
 *
 * <p>The flag destination is stored in varp 2807 as a packed tile hash:
 * {@code flagX = (hash >> 14) & 0x3FFF}, {@code flagY = hash & 0x3FFF},
 * {@code flagPlane = (hash >> 28) & 0x3}.</p>
 *
 * <p>If no flag is set (varp == 0), the script waits. Once a flag is
 * detected it walks there using the world pathfinder and logs the result.</p>
 */
@ScriptManifest(
        name = "Walk to Flag",
        version = "1.0",
        author = "BotWithUs",
        description = "Walks to the blue world-map marker using world pathfinding",
        category = ScriptCategory.UTILITY
)
public class WalkToFlagScript implements BotScript {

    private static final Logger log = LoggerFactory.getLogger(WalkToFlagScript.class);
    private static final int FLAG_VARP = 2807;

    private ScriptContext ctx;

    @Override
    public void onStart(ScriptContext ctx) {
        this.ctx = ctx;
        log.info("Walk to Flag script started — set a flag on the world map!");
    }

    @Override
    public int onLoop() {
        GameAPI api = ctx.getGameAPI();
        int tileHash = api.getVarp(FLAG_VARP);

        if (tileHash == 0) {
            log.debug("No world-map flag set, waiting...");
            return 1000;
        }

        int flagX = (tileHash >> 14) & 0x3FFF;
        int flagY = tileHash & 0x3FFF;
        int flagPlane = (tileHash >> 28) & 0x3;

        log.info("Flag detected at ({}, {}, plane {}), walking...", flagX, flagY, flagPlane);

        Navigation nav = ctx.getNavigation();
        WalkResult result = nav.walkWorldPath(flagX, flagY, flagPlane);

        switch (result) {
            case ARRIVED   -> log.info("Arrived at ({}, {})", flagX, flagY);
            case CANCELLED -> log.warn("Walk cancelled before reaching ({}, {})", flagX, flagY);
            case FAILED    -> log.warn("Walk failed to reach ({}, {})", flagX, flagY);
            case TIMEOUT   -> log.warn("Walk timed out heading to ({}, {})", flagX, flagY);
        }

        return 1000;
    }

    @Override
    public void onStop() {
        log.info("Walk to Flag script stopped.");
    }
}
