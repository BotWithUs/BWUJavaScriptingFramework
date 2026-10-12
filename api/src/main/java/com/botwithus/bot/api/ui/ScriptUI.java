package com.botwithus.bot.api.ui;

/**
 * Interface for scripts that provide custom UI.
 * Implement this and return it from {@link com.botwithus.bot.api.BotScript#getUI()}.
 *
 * <p>Use ImGui directly to draw widgets. Scripts should {@code requires imgui.binding;}
 * in their module-info to access the ImGui API.</p>
 *
 * <p>The host draws each script's UI in an ImGui context of its own, on a thread of
 * its own, while its UI thread waits; what the UI drew is then shown in its panel.
 * A UI that breaks one of Dear ImGui's rules, such as popping a style it never
 * pushed or beginning a window it never ends, is stopped and reported in its panel
 * instead of taking the host down.</p>
 */
public interface ScriptUI {

    /**
     * Called every frame to render this script's UI.
     * Use {@code imgui.ImGui} directly to draw widgets.
     *
     * <p>Keep rendering fast: the host's UI thread waits for it, and there is no
     * timeout. Everything drawn here, popups and tooltips included, stays inside
     * the script's panel. The host's own windows are not reachable. Do not call
     * OpenGL from here: this does not run on the thread that owns the GL context.
     * A {@link RuntimeException} is shown in the panel and the UI is drawn again
     * next frame.</p>
     */
    void render();
}
