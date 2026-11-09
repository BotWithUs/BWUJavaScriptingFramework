package com.botwithus.bot.cli.gui.scriptui;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ui.ScriptUI;

import imgui.ImGui;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A script carrying the {@code getUI()} sample from the script-author wiki's
 * API tour ({@code java/api-tour.md}). The body of {@link #getUI()} is the
 * wiki's, word for word, except that its {@code // handle click} branch counts
 * the click so a test can see it ran. Change it only together with the article.
 */
final class WikiSampleScript implements BotScript {

    /** One server tick: the script has nothing to do, and must not spin. */
    private static final int LOOP_DELAY_MS = 600;

    private final float progress;
    private final AtomicInteger clicks = new AtomicInteger();

    WikiSampleScript(float progress) {
        this.progress = progress;
    }

    @Override
    public void onStart(ScriptContext ctx) {
    }

    @Override
    public int onLoop() {
        return LOOP_DELAY_MS;
    }

    @Override
    public void onStop() {
    }

    @Override
    public ScriptUI getUI() {
        return () -> {
            ImGui.text("Status: running");
            if (ImGui.button("Do something")) {
                clicks.incrementAndGet();
            }
            ImGui.progressBar(progress, -1, 0, "Progress");
        };
    }

    /** How many times the click branch has run. */
    int clicks() {
        return clicks.get();
    }
}
