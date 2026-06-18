package com.botwithus.bot.scripts.sdntest;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;

/**
 * Minimal BotScript used by the SDN end-to-end test. It is packaged into an
 * encrypted SDN bundle and loaded by the custom JVM's {@code SdnClassLoader};
 * the test proves the class is discovered via {@code ServiceLoader}, that its
 * defining loader is the SDN loader, and that its lifecycle methods run.
 *
 * <p>Deliberately null-safe and context-free so the test can drive it without a
 * live game session.</p>
 */
public class SdnDummyScript implements BotScript {

    /** Observable marker so the test can confirm it loaded the decrypted class. */
    public static final String MARKER = "SDN_DUMMY_OK";

    private int loops;

    @Override
    public void onStart(ScriptContext ctx) {
        loops = 0;
    }

    @Override
    public int onLoop() {
        loops++;
        return -1; // run exactly once, then stop
    }

    @Override
    public void onStop() {
        // nothing to clean up
    }

    /** Number of times {@link #onLoop()} has run; used by the test. */
    public int loops() {
        return loops;
    }
}
