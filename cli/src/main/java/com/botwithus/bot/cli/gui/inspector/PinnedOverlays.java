package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.core.window.ClientArea;
import com.botwithus.bot.core.window.GameWindows;

import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.ImGuiWindowClass;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiViewportFlags;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The OS-window side of pop-outs pinned as overlays. A pinned window lets the
 * mouse through to whatever is under it and is see-through. Holding the
 * interact keys ({@link InteractKeys}, Ctrl unless the user changed them) with
 * the cursor over it makes it take the mouse and draw opaque for as long as
 * they are held, so its own buttons can be clicked.
 *
 * <p>A window is pinned either above every window, or to the game client its
 * script runs on. Pinned to a game, its OS window is owned by the game's
 * window: Windows keeps an owned window above its owner, and only above it, so
 * other applications cover both, and it hides with the game. It follows the
 * game's client area at an offset the user sets by dragging it with the keys held,
 * kept inside that area, and is not drawn while the game is minimised. Should
 * Windows refuse the owner, the window is instead drawn, topmost, only while
 * the game or the window itself is the foreground window. A game window that
 * is never found leaves the window pinned above every window. One that goes
 * away hides the window until the game shows a new one, and unpins it once the
 * game's process has exited.</p>
 *
 * <p>The mouse pass-through is the viewport's {@code NoInputs} flag, set through
 * the window class each frame: the GLFW backend turns it into
 * {@code GLFW_MOUSE_PASSTHROUGH} at the start of every frame, and Dear ImGui
 * stops routing the mouse to the window. Topmost, owner and opacity are set on
 * the OS window here, and only when they change, because the backend reads
 * {@code TopMost} only when it creates a window and sets no owner or opacity.</p>
 *
 * <p>The pinned window has no input focus and, passing the mouse through, gets
 * no mouse events, so the keys and the cursor are polled from the whole desktop:
 * {@code GetAsyncKeyState} on Windows, and the cursor through GLFW, which reads
 * it relative to any window whether focused or not.</p>
 *
 * <p>Render thread only. GLFW is called only on a window ImGui created this
 * session and drew this frame, never on the host's own window.</p>
 */
final class PinnedOverlays {

    private static final Logger log = LoggerFactory.getLogger(PinnedOverlays.class);

    /** How opaque a pinned window is while it lets the mouse through. */
    static final float PINNED_OPACITY = 0.75f;
    private static final float OPAQUE = 1f;
    /** How long a game window not found is left before the desktop is searched again. */
    private static final long RESCAN_NANOS = 1_000_000_000L;
    private static final long NO_WINDOW = 0L;
    private static final int PINNED_FLAGS = ImGuiViewportFlags.NoAutoMerge
            | ImGuiViewportFlags.NoFocusOnAppearing | ImGuiViewportFlags.NoTaskBarIcon;

    /** Whether a pinned window is drawn this frame. */
    enum Visibility {
        SHOWN,
        /** The game it is pinned to is minimised, or not in front without an owner. */
        HIDDEN,
        /** The game window it was pinned to is gone: unpin it. */
        GAME_GONE
    }

    /** A pinned window letting the mouse through, above every window. */
    private final ImGuiWindowClass passThrough = new ImGuiWindowClass();
    /** A pinned window with the interact keys held over it, taking the mouse, above every window. */
    private final ImGuiWindowClass interactive = new ImGuiWindowClass();
    /** {@link #passThrough} for a window pinned to a game, which its owner keeps in place, not topmost. */
    private final ImGuiWindowClass passThroughOnGame = new ImGuiWindowClass();
    /** {@link #interactive} for a window pinned to a game. */
    private final ImGuiWindowClass interactiveOnGame = new ImGuiWindowClass();
    /** Each drawn window's OS window, by ImGui title, as of the frame it was last drawn. */
    private final Map<String, Applied> windows = new HashMap<>();
    private final Set<String> drawnThisFrame = new HashSet<>();
    /** The game each window is pinned to this frame, and where the game is. */
    private final Map<String, OnGame> onGameThisFrame = new HashMap<>();
    /** Each window's game window, once found. Lives for the session. */
    private final Map<String, Long> games = new HashMap<>();
    /** Windows whose game window was found once: while it is replaced they hide rather than float above all. */
    private final Set<String> everFound = new HashSet<>();
    /** When each window whose game window was not found may search again. */
    private final Map<String, Long> nextSearchAt = new HashMap<>();
    /** Where each window sits on its game. Lives for the session. */
    private final Map<String, GameOffset> offsets = new HashMap<>();
    /** Each window's size as last drawn. Lives for the session. */
    private final Map<String, Size> sizes = new HashMap<>();
    /** Game windows Windows would not let own a window: these get the foreground fallback. */
    private final Set<Long> unownable = new HashSet<>();
    private final double[] cursorX = new double[1];
    private final double[] cursorY = new double[1];
    private final int[] width = new int[1];
    private final int[] height = new int[1];

    /**
     * What was last set on one OS window.
     *
     * @param window the Win32 window, or 0 where none was asked for
     * @param owner  the window set as its owner, or 0 for none
     */
    private record Applied(long handle, long window, boolean isTopmost, float opacity, long owner) {
    }

    /** A window pinned to the game window {@code game}, whose client area is {@code area} this frame. */
    private record OnGame(long game, ClientArea area) {
    }

    private record Size(float width, float height) {
    }

    PinnedOverlays() {
        int topmost = PINNED_FLAGS | ImGuiViewportFlags.TopMost;
        passThrough.setViewportFlagsOverrideSet(topmost | ImGuiViewportFlags.NoInputs);
        interactive.setViewportFlagsOverrideSet(topmost);
        passThroughOnGame.setViewportFlagsOverrideSet(PINNED_FLAGS | ImGuiViewportFlags.NoInputs);
        interactiveOnGame.setViewportFlagsOverrideSet(PINNED_FLAGS);
    }

    /** Called before the frame's windows are drawn. */
    void beginFrame() {
        drawnThisFrame.clear();
        onGameThisFrame.clear();
    }

    /**
     * Whether the pinned window {@code title} takes the mouse this frame: every
     * key of {@code keys} is held and the cursor is over its OS window as it was
     * last frame. False for a window whose OS window does not exist yet.
     */
    boolean isInteractive(String title, InteractKeys keys) {
        Applied applied = windows.get(title);
        return applied != null && keys.isHeld(key -> DesktopKeys.isDown(key, applied.handle()))
                && isCursorOver(applied.handle());
    }

    /**
     * Whether the pinned window {@code title} is drawn this frame. Called before
     * anything else of its frame. {@code gamePid} is the process of the game it
     * is pinned to, or empty for a window pinned above every window.
     */
    Visibility visibility(String title, OptionalLong gamePid) {
        if (gamePid.isEmpty()) {
            return Visibility.SHOWN;
        }
        long pid = gamePid.getAsLong();
        long known = games.getOrDefault(title, NO_WINDOW);
        if (known != NO_WINDOW && !GameWindows.isWindowOf(known, pid)) {
            games.remove(title);
            if (!isAlive(pid)) {
                return Visibility.GAME_GONE;
            }
        }
        long game = gameWindow(title, pid);
        if (game == NO_WINDOW) {
            return everFound.contains(title) ? Visibility.HIDDEN : Visibility.SHOWN;
        }
        Optional<ClientArea> area = GameWindows.shownClientArea(game);
        if (area.isEmpty() || unownable.contains(game) && !isInFront(title, game)) {
            return Visibility.HIDDEN;
        }
        onGameThisFrame.put(title, new OnGame(game, area.get()));
        return Visibility.SHOWN;
    }

    /**
     * Whether the game process is still running. A game window that goes while
     * it runs is being replaced, as when the client switches display mode, so
     * the window hides until the new one is found rather than unpinning.
     */
    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** The window {@code title}'s game window, searching for it at most once per {@link #RESCAN_NANOS}. */
    private long gameWindow(String title, long pid) {
        long known = games.getOrDefault(title, NO_WINDOW);
        if (known != NO_WINDOW) {
            return known;
        }
        long now = System.nanoTime();
        if (now - nextSearchAt.getOrDefault(title, now) < 0) {
            return NO_WINDOW;
        }
        OptionalLong found = GameWindows.mainWindowOf(pid);
        if (found.isEmpty()) {
            nextSearchAt.put(title, now + RESCAN_NANOS);
            return NO_WINDOW;
        }
        nextSearchAt.remove(title);
        games.put(title, found.getAsLong());
        everFound.add(title);
        return found.getAsLong();
    }

    /** The game, or the pinned window itself after a click with the keys held, is the window in front. */
    private boolean isInFront(String title, long game) {
        Applied applied = windows.get(title);
        return GameWindows.isForeground(game) || applied != null && GameWindows.isForeground(applied.window());
    }

    /**
     * Places a window pinned to a game on it, after the host's own
     * {@code setNextWindow*} calls and before its {@code begin}. While the
     * window takes the mouse it is left where the user drags it. Does nothing
     * for a window pinned above every window.
     *
     * @param defaultWidth  the window's width the first time it is drawn at all
     * @param defaultHeight the window's height the first time it is drawn at all
     * @param margin        the default place's distance from the game's top and right edges
     */
    void place(String title, boolean isInteractive, float defaultWidth, float defaultHeight, float margin) {
        OnGame onGame = onGameThisFrame.get(title);
        if (onGame == null || isInteractive) {
            return;
        }
        ClientArea area = onGame.area();
        Size size = sizes.computeIfAbsent(title, t -> new Size(defaultWidth, defaultHeight));
        GameOffset offset = offsets.computeIfAbsent(title,
                t -> GameOffset.topRight(area, size.width(), size.height(), margin))
                .clampedTo(area, size.width(), size.height());
        ImGui.setNextWindowPos(offset.screenX(area), offset.screenY(area), ImGuiCond.Always);
    }

    /** The window class a pinned window is given before its {@code begin}. */
    ImGuiWindowClass windowClass(String title, boolean isInteractive) {
        if (onGameThisFrame.containsKey(title)) {
            return isInteractive ? interactiveOnGame : passThroughOnGame;
        }
        return isInteractive ? interactive : passThrough;
    }

    /**
     * Called between {@code begin} and {@code end} of every popped-out window,
     * pinned or not: brings its OS window's topmost, owner and opacity in line
     * with how it is pinned and whether it is interactive, and keeps where it
     * sits on its game when the user moved it. An unpinned window is put back
     * to an ordinary one. Does nothing while its OS window is still to be created.
     */
    void afterBegin(String title, boolean isPinned, boolean isInteractive) {
        drawnThisFrame.add(title);
        sizes.put(title, new Size(ImGui.getWindowWidth(), ImGui.getWindowHeight()));
        OnGame onGame = isPinned ? onGameThisFrame.get(title) : null;
        if (onGame != null) {
            offsets.put(title, GameOffset.at(onGame.area(), ImGui.getWindowPosX(), ImGui.getWindowPosY()));
        }
        ImGuiViewport viewport = ImGui.getWindowViewport();
        if (!viewport.getPlatformWindowCreated() || viewport.getID() == ImGui.getMainViewport().getID()) {
            return;
        }
        long handle = viewport.getPlatformHandle();
        Applied last = windows.get(title);
        if (last == null || last.handle() != handle) {
            last = new Applied(handle, NO_WINDOW, false, OPAQUE, NO_WINDOW);
        }
        windows.put(title, apply(last, wanted(handle, last, isPinned, isInteractive, onGame)));
    }

    /** What the OS window should have now. */
    private Applied wanted(long handle, Applied last, boolean isPinned, boolean isInteractive, OnGame onGame) {
        float opacity = isPinned && !isInteractive ? PINNED_OPACITY : OPAQUE;
        long window = onGame != null || last.owner() != NO_WINDOW ? win32Window(handle, last) : last.window();
        if (onGame == null || unownable.contains(onGame.game())) {
            return new Applied(handle, window, isPinned, opacity, NO_WINDOW);
        }
        return new Applied(handle, window, false, opacity, onGame.game());
    }

    /** Sets on the OS window what changed since {@code last}; returns what it now has. */
    private Applied apply(Applied last, Applied wanted) {
        Applied result = wanted;
        if (wanted.owner() != last.owner() && !GameWindows.setOwner(wanted.window(), wanted.owner())
                && wanted.owner() != NO_WINDOW) {
            log.warn("Windows would not let the game window own a pinned overlay; pinning it in front "
                    + "of the game only while the game is the foreground window instead");
            unownable.add(wanted.owner());
            result = new Applied(wanted.handle(), wanted.window(), true, wanted.opacity(), NO_WINDOW);
        }
        long handle = result.handle();
        if (result.isTopmost() != last.isTopmost()) {
            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_FLOATING, result.isTopmost() ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        }
        if (result.opacity() != last.opacity()) {
            GLFW.glfwSetWindowOpacity(handle, result.opacity());
        }
        return result;
    }

    /** The Win32 window behind the GLFW window {@code handle}. */
    private static long win32Window(long handle, Applied last) {
        return last.window() != NO_WINDOW ? last.window() : GLFWNativeWin32.glfwGetWin32Window(handle);
    }

    /**
     * Called after the frame's windows are drawn: forgets every window not drawn
     * this frame, whose OS window ImGui destroys, so its handle is never used again.
     */
    void endFrame() {
        windows.keySet().retainAll(drawnThisFrame);
    }

    private boolean isCursorOver(long handle) {
        GLFW.glfwGetCursorPos(handle, cursorX, cursorY);
        GLFW.glfwGetWindowSize(handle, width, height);
        return cursorX[0] >= 0 && cursorY[0] >= 0 && cursorX[0] < width[0] && cursorY[0] < height[0];
    }
}
