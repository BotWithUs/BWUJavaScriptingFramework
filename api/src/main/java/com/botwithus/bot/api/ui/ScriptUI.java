package com.botwithus.bot.api.ui;

/**
 * Interface for scripts that provide custom UI.
 * Implement this and return it from {@link com.botwithus.bot.api.BotScript#getUI()}.
 *
 * <p>The {@link #render()} method is called every frame on the UI thread.
 * Use ImGui directly to draw widgets. Scripts should {@code requires imgui.binding;}
 * in their module-info to access the ImGui API.</p>
 */
public interface ScriptUI {

    /**
     * Called every frame to render this script's UI.
     * Use {@code imgui.ImGui} directly to draw widgets.
     * Keep rendering fast — this runs on the UI thread.
     */
    void render();
}
