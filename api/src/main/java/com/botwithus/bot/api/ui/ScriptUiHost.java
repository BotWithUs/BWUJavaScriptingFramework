package com.botwithus.bot.api.ui;

/**
 * The host's handle on a script's UI, passed to {@link ScriptUI#attach(ScriptUiHost)}
 * before every frame. A script that draws its own window chrome uses it for its
 * close button. Call it from {@link ScriptUI#render()}. A script with its own
 * help or support buttons uses it to open the host's report dialog.
 */
public interface ScriptUiHost {

    /**
     * Closes the script's pop-out window and returns the UI to the host's
     * drawer, as the host's title-bar X does. Does nothing when the UI is docked.
     */
    void close();

    /** True when the UI is in its own pop-out window, false when docked in the host. */
    boolean poppedOut();

    /**
     * Pins the UI as an overlay, or unpins it. A pinned UI is popped out if it
     * was not, and its window stays on top of other windows, lets the mouse
     * through to whatever is under it, and is see-through. Holding Ctrl with
     * the cursor over it makes it interactive and opaque for as long as Ctrl
     * is held; see {@link #overlayInteractive()}. Unpinning leaves the window
     * popped out, as an ordinary one. Takes effect from the next frame. A host
     * that cannot pin does nothing.
     */
    default void setPinnedOverlay(boolean pinned) {
    }

    /** True when the UI is pinned as an overlay; always false on a host that cannot pin. */
    default boolean pinnedOverlay() {
        return false;
    }

    /**
     * True while a pinned UI takes the mouse because the user holds Ctrl over
     * it; false when it lets the mouse through, and when it is not pinned. The
     * host reads Ctrl from the whole desktop, so this holds while the game has
     * the keyboard focus, when the UI's own key state would not.
     */
    default boolean overlayInteractive() {
        return false;
    }

    /**
     * The keys the user holds to make a pinned UI interactive, as the host's
     * settings name them for a hint, such as {@code "Ctrl"} or
     * {@code "Ctrl+Shift"}. Wherever the docs above say Ctrl, these keys are
     * meant. {@code "Ctrl"} on a host whose keys cannot be changed.
     */
    default String overlayInteractKeys() {
        return "Ctrl";
    }

    /**
     * Opens the host's "Report a problem" dialog about this script on the game
     * client it runs on, as the flag button on the script's row in the host
     * does: the user says what went wrong and adds a note, and the host sends
     * the script's run logs with it and shows the report code it gets back.
     * The dialog opens in the host's own window from the next frame; this
     * returns at once. It may be called from {@link ScriptUI#render()} or from
     * any other thread.
     *
     * @return true when the host took the request and its dialog will open;
     *         false when this host cannot report from a script's UI, such as an
     *         older host or a script with no game client of its own, and the
     *         script should offer its own way to get help instead
     */
    default boolean reportProblem() {
        return false;
    }
}
