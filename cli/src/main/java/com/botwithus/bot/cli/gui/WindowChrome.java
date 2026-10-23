package com.botwithus.bot.cli.gui;

/**
 * The parts of the window frame the app draws itself: the window buttons and
 * drag region in the top bar, and the resize border around the edge.
 *
 * <p>{@link FramelessChrome} draws them for the default frameless window.
 * {@link NativeChrome} draws nothing, for when Windows' own frame is on
 * ({@code ui.nativeFrame}) and already provides all three.</p>
 */
public sealed interface WindowChrome permits FramelessChrome, NativeChrome {

    /** Width the window buttons take in the top bar; 0 when there are none. */
    float controlsWidth();

    /** Draws the minimise, maximise/restore and close buttons ending at {@code right}. */
    void renderControls(float right, float y, float height);

    /**
     * Makes the given area of the top bar a handle that moves the window, and
     * maximises or restores it on double-click. Call after every control in the
     * area, so the controls keep the mouse and the handle gets only the gaps.
     */
    void renderDragRegion(float x, float y, float width, float height);

    /** Draws the resize border. Call once per frame, after everything else in the shell. */
    void renderEdges();
}
