package com.botwithus.bot.cli.gui.inspector;

import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.ImGuiWindowClass;
import imgui.flag.ImGuiViewportFlags;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.Platform;
import org.lwjgl.system.windows.User32;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The OS-window side of pop-outs pinned as overlays. A pinned window stays on
 * top of other windows, the game included, lets the mouse through to whatever
 * is under it, and is see-through. Holding Ctrl with the cursor over it makes it
 * take the mouse and draw opaque for as long as Ctrl is held, so its own
 * buttons can be clicked.
 *
 * <p>The mouse pass-through is the viewport's {@code NoInputs} flag, set through
 * the window class each frame: the GLFW backend turns it into
 * {@code GLFW_MOUSE_PASSTHROUGH} at the start of every frame, and Dear ImGui
 * stops routing the mouse to the window. Topmost and opacity are set on the OS
 * window here, and only when they change, because the backend reads
 * {@code TopMost} only when it creates a window and never sets an opacity.</p>
 *
 * <p>The pinned window has no input focus and, passing the mouse through, gets
 * no mouse events, so Ctrl and the cursor are polled from the whole desktop:
 * {@code GetAsyncKeyState} on Windows, and the cursor through GLFW, which reads
 * it relative to any window whether focused or not.</p>
 *
 * <p>Render thread only. GLFW is called only on a window ImGui created this
 * session and drew this frame, never on the host's own window.</p>
 */
final class PinnedOverlays {

    /** How opaque a pinned window is while it lets the mouse through. */
    static final float PINNED_OPACITY = 0.75f;
    private static final float OPAQUE = 1f;
    /** The bit of {@code GetAsyncKeyState}'s result that means the key is down now. */
    private static final int KEY_DOWN_BIT = 0x8000;
    private static final int PINNED_FLAGS = ImGuiViewportFlags.NoAutoMerge | ImGuiViewportFlags.TopMost
            | ImGuiViewportFlags.NoFocusOnAppearing | ImGuiViewportFlags.NoTaskBarIcon;

    /** A pinned window letting the mouse through. Built once; lives as long as the GUI. */
    private final ImGuiWindowClass passThrough = new ImGuiWindowClass();
    /** A pinned window with Ctrl held over it, taking the mouse. */
    private final ImGuiWindowClass interactive = new ImGuiWindowClass();
    /** Each drawn window's OS window, by ImGui title, as of the frame it was last drawn. */
    private final Map<String, Applied> windows = new HashMap<>();
    private final Set<String> drawnThisFrame = new HashSet<>();
    private final double[] cursorX = new double[1];
    private final double[] cursorY = new double[1];
    private final int[] width = new int[1];
    private final int[] height = new int[1];

    /** What was last set on one OS window. */
    private record Applied(long handle, boolean isTopmost, float opacity) {
    }

    PinnedOverlays() {
        passThrough.setViewportFlagsOverrideSet(PINNED_FLAGS | ImGuiViewportFlags.NoInputs);
        interactive.setViewportFlagsOverrideSet(PINNED_FLAGS);
    }

    /** Called before the frame's windows are drawn. */
    void beginFrame() {
        drawnThisFrame.clear();
    }

    /**
     * Whether the pinned window {@code title} takes the mouse this frame: Ctrl is
     * held and the cursor is over its OS window as it was last frame. False for
     * a window whose OS window does not exist yet.
     */
    boolean isInteractive(String title) {
        Applied applied = windows.get(title);
        return applied != null && isCtrlDown(applied.handle()) && isCursorOver(applied.handle());
    }

    /** The window class a pinned window is given before its {@code begin}. */
    ImGuiWindowClass windowClass(boolean isInteractive) {
        return isInteractive ? interactive : passThrough;
    }

    /**
     * Called between {@code begin} and {@code end} of every popped-out window,
     * pinned or not: brings its OS window's topmost and opacity in line with
     * whether it is pinned and interactive. An unpinned window is put back to
     * an ordinary one. Does nothing while its OS window is still to be created.
     */
    void afterBegin(String title, boolean isPinned, boolean isInteractive) {
        drawnThisFrame.add(title);
        ImGuiViewport viewport = ImGui.getWindowViewport();
        if (!viewport.getPlatformWindowCreated() || viewport.getID() == ImGui.getMainViewport().getID()) {
            return;
        }
        long handle = viewport.getPlatformHandle();
        Applied last = windows.get(title);
        if (last == null || last.handle() != handle) {
            last = new Applied(handle, false, OPAQUE);
        }
        Applied wanted = new Applied(handle, isPinned, isPinned && !isInteractive ? PINNED_OPACITY : OPAQUE);
        if (wanted.isTopmost() != last.isTopmost()) {
            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_FLOATING, wanted.isTopmost() ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        }
        if (wanted.opacity() != last.opacity()) {
            GLFW.glfwSetWindowOpacity(handle, wanted.opacity());
        }
        windows.put(title, wanted);
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

    /**
     * Ctrl as the desktop sees it. GLFW's key state is per window and updated
     * only for the focused one, which is the game's, so Windows is asked directly.
     */
    private static boolean isCtrlDown(long handle) {
        if (Platform.get() == Platform.WINDOWS) {
            return (User32.GetAsyncKeyState(User32.VK_CONTROL) & KEY_DOWN_BIT) != 0;
        }
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
    }
}
