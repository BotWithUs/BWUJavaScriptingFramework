package com.botwithus.bot.api.ui;

/**
 * The host's handle on a script's UI, passed to {@link ScriptUI#attach(ScriptUiHost)}
 * before every frame. A script that draws its own window chrome uses it for its
 * close button. Call it from {@link ScriptUI#render()}.
 */
public interface ScriptUiHost {

    /**
     * Closes the script's pop-out window and returns the UI to the host's
     * drawer, as the host's title-bar X does. Does nothing when the UI is docked.
     */
    void close();

    /** True when the UI is in its own pop-out window, false when docked in the host. */
    boolean poppedOut();
}
