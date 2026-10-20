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
 *
 * <p>By default the host frames the UI: a titled window when popped out, a
 * dashed frame in its drawer. A UI can draw that chrome itself instead; see
 * {@link #drawsOwnChrome()}.</p>
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

    /**
     * True when the script draws its own window chrome: its background, and a
     * close button that calls {@link ScriptUiHost#close()}. The host then pops
     * the UI out into a frameless window with no title bar, close button,
     * padding, border or background of its own, transparent wherever the UI
     * draws nothing, and docked in the host it draws no frame around it.
     *
     * <p>The host moves a frameless window when the user drags a spot of the UI
     * that holds none of its widgets, and resizes it from its edges. The UI does
     * not move the window itself: the host's own windows stay out of its reach,
     * as described on {@link #render()}.</p>
     *
     * <p>Answer from the UI's own state only. This is called inside the UI's
     * ImGui frame, after {@link #render()}, but it is a question, not a place to
     * draw, so do not call ImGui from it. A {@link RuntimeException} reads as
     * false. The first time the host needs the answer, before it has shown the
     * UI anywhere, it renders the UI once in a frame nobody sees to ask it.
     * While the UI is stopped for breaking an ImGui rule, or in a frame after
     * {@code render()} threw, the host draws its own window around it anyway,
     * so the user can still close it.</p>
     */
    default boolean drawsOwnChrome() {
        return false;
    }

    /**
     * Called before every {@link #render()}, on the same thread, with the
     * handle for the place the UI is drawn this frame: a pop-out window or the
     * host's drawer. A UI can move between the two, so keep the latest handle.
     * A {@link RuntimeException} here is treated like one from
     * {@code render()}: shown in the panel, and the UI is tried again next frame.
     */
    default void attach(ScriptUiHost host) {
    }
}
