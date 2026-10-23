package com.botwithus.sdnlive.${variant};

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A script that does nothing in the game. It exists to be delivered, loaded and
 * started, and to say from inside the module it was loaded as that it ran.
 */
@ScriptManifest(name = "SDN live ${variant}", author = "BotWithUs",
        description = "Payload for the SDN install-screens live check; does nothing in the game.")
public final class LiveFixtureScript implements BotScript {

    private static final Logger log = LoggerFactory.getLogger(LiveFixtureScript.class);

    /** One server tick: the script only needs to stay alive until it is stopped. */
    private static final int LOOP_DELAY_MS = 600;

    @Override
    public void onStart(ScriptContext ctx) {
        Module module = getClass().getModule();
        log.info("SDN-LIVE fixture {} started in module {} (named={})",
                getClass().getName(), module.getName(), module.isNamed());
    }

    @Override
    public int onLoop() {
        return LOOP_DELAY_MS;
    }

    @Override
    public void onStop() {
        log.info("SDN-LIVE fixture {} stopped", getClass().getName());
    }
}
