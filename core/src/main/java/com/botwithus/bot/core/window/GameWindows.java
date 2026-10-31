package com.botwithus.bot.core.window;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * The desktop windows of game clients, found by the client's process id, so a
 * host window can sit over the right one when several clients run.
 *
 * <p>Windows only: on any other platform nothing is found and nothing is set,
 * and user32 is never loaded. Window handles are Win32 {@code HWND} values
 * carried as {@code long}; 0 is no window.</p>
 */
public final class GameWindows {

    /** Stops the walk over the desktop's top-level windows should it never end. */
    private static final int MAX_TOP_LEVEL_WINDOWS = 65_536;
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    private GameWindows() {
    }

    /**
     * The game window of the process {@code pid}: its largest visible top-level
     * window that no other window owns, or empty when it shows none.
     */
    public static OptionalLong mainWindowOf(long pid) {
        if (!IS_WINDOWS) {
            return OptionalLong.empty();
        }
        long best = 0L;
        long bestArea = -1L;
        long window = User32.nextTopLevelWindow(0L);
        for (int seen = 0; window != 0L && seen < MAX_TOP_LEVEL_WINDOWS; seen++) {
            if (isCandidate(window, pid)) {
                long area = User32.clientAreaOnScreen(window)
                        .map(a -> (long) a.width() * a.height()).orElse(0L);
                if (area > bestArea) {
                    best = window;
                    bestArea = area;
                }
            }
            window = User32.nextTopLevelWindow(window);
        }
        return best == 0L ? OptionalLong.empty() : OptionalLong.of(best);
    }

    private static boolean isCandidate(long window, long pid) {
        return User32.processIdOf(window) == pid && User32.isWindowVisible(window) && User32.ownerOf(window) == 0L;
    }

    /** Whether {@code window} still exists and still belongs to the process {@code pid}. */
    public static boolean isWindowOf(long window, long pid) {
        return IS_WINDOWS && window != 0L && User32.isWindow(window) && User32.processIdOf(window) == pid;
    }

    /** {@code window}'s client area on the desktop, or empty while it is hidden or minimised. */
    public static Optional<ClientArea> shownClientArea(long window) {
        if (!IS_WINDOWS || !User32.isWindowVisible(window) || User32.isIconic(window)) {
            return Optional.empty();
        }
        return User32.clientAreaOnScreen(window);
    }

    /** Whether {@code window} is the one the user is working in. */
    public static boolean isForeground(long window) {
        return IS_WINDOWS && window != 0L && User32.foregroundWindow() == window;
    }

    /**
     * Makes {@code owner} the owner of the top-level window {@code window}, or
     * clears it with 0. An owned window stays above its owner, and hides and
     * shows with it, without being above any other window. Returns whether
     * Windows now reports that owner.
     */
    public static boolean setOwner(long window, long owner) {
        if (!IS_WINDOWS || window == 0L) {
            return false;
        }
        User32.setWindowLongPtr(window, User32.GWLP_HWNDPARENT, owner);
        return User32.windowLongPtr(window, User32.GWLP_HWNDPARENT) == owner;
    }
}
