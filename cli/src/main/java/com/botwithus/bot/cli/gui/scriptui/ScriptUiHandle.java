package com.botwithus.bot.cli.gui.scriptui;

import com.botwithus.bot.api.ui.ScriptUiHost;

import java.util.function.Consumer;

/**
 * The handle a script's UI is attached to before each frame it draws: whether
 * it is in a pop-out window, whether that window is pinned as an overlay, and
 * what its own close and pin buttons do.
 *
 * <p>{@link #close()} and {@link #setPinnedOverlay} are called from the
 * script's UI, so they run on that UI's render thread while the host's UI
 * thread waits for the frame. {@code onClose} and {@code onPin} may therefore
 * touch UI-thread state, but must not call Dear ImGui: the sandbox's context is
 * current, not the host's.</p>
 *
 * @param onClose             brings a popped-out UI back into the drawer; does nothing when docked
 * @param poppedOut           true when the UI is in its own pop-out window
 * @param onPin               pins the UI as an overlay, popping it out first, or unpins it
 * @param pinnedOverlay       true when the UI's window is pinned as an overlay
 * @param overlayInteractive  true while a pinned window takes the mouse because its interact keys are held over it
 * @param overlayInteractKeys the interact keys as the user set them, such as {@code Ctrl+Shift}
 */
public record ScriptUiHandle(Runnable onClose, boolean poppedOut, Consumer<Boolean> onPin, boolean pinnedOverlay,
                             boolean overlayInteractive, String overlayInteractKeys) implements ScriptUiHost {

    /** The keys a host has unless the user changes them. */
    public static final String DEFAULT_INTERACT_KEYS = "Ctrl";

    /** The handle for a UI drawn in the drawer, which has no window of its own to close or pin. */
    public static final ScriptUiHandle DOCKED = new ScriptUiHandle(() -> { }, false);

    /** A handle that cannot pin: its pin button does nothing and it never reads as pinned. */
    public ScriptUiHandle(Runnable onClose, boolean poppedOut) {
        this(onClose, poppedOut, pinned -> { }, false, false, DEFAULT_INTERACT_KEYS);
    }

    @Override
    public void close() {
        onClose.run();
    }

    @Override
    public void setPinnedOverlay(boolean pinned) {
        onPin.accept(pinned);
    }
}
