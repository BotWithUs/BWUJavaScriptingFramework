package com.botwithus.bot.cli.gui.scriptui;

import com.botwithus.bot.api.ui.ScriptUiHost;

/**
 * The handle a script's UI is attached to before each frame it draws: whether
 * it is in a pop-out window, and what its own close button does.
 *
 * <p>{@link #close()} is called from the script's UI, so it runs on that UI's
 * render thread while the host's UI thread waits for the frame. {@code onClose}
 * may therefore touch UI-thread state, but must not call Dear ImGui: the
 * sandbox's context is current, not the host's.</p>
 *
 * @param onClose   brings a popped-out UI back into the drawer; does nothing when docked
 * @param poppedOut true when the UI is in its own pop-out window
 */
public record ScriptUiHandle(Runnable onClose, boolean poppedOut) implements ScriptUiHost {

    /** The handle for a UI drawn in the drawer, which has no window of its own to close. */
    public static final ScriptUiHandle DOCKED = new ScriptUiHandle(() -> { }, false);

    @Override
    public void close() {
        onClose.run();
    }
}
