package com.botwithus.bot.cli.gui.scriptui;

import com.botwithus.bot.cli.gui.window.BorderHitTest;
import com.botwithus.bot.cli.gui.window.ResizeDrag;
import com.botwithus.bot.cli.gui.window.ResizeEdge;
import com.botwithus.bot.cli.gui.window.ScreenPoint;
import com.botwithus.bot.cli.gui.window.WindowRect;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiMouseButton;

import java.util.Optional;

/**
 * Resizes a frameless window that holds a script's UI from a band along its
 * edges, with wider corners, as the host's own frameless window resizes.
 * Dear ImGui's own edge resize cannot do this: the panel a sandboxed UI is
 * composited into covers the window with a child window and one input item,
 * so ImGui never sees a press on the window's border, and the panel sets the
 * script's cursor over ImGui's resize cursor every frame.
 *
 * <p>So the host hit-tests the band itself, before the UI is drawn. While the
 * mouse is in the band, or a resize is under way, the band has the pointer:
 * {@link #takesPointer} says so, the caller draws the UI with the pointer taken,
 * and the script sees no hover and no press there, which also means no
 * {@link BackgroundDrag} starts. A press anywhere else is the panel's, as
 * before. The band is only offered while no button is held, so a drag that
 * began on one of the script's widgets keeps going when it crosses the edge.</p>
 *
 * <p>The geometry is {@link BorderHitTest}'s and {@link ResizeDrag}'s, shared
 * with the host's window. The window is sized with {@code setNextWindowPos} and
 * {@code setNextWindowSize}, so with viewports on, Dear ImGui resizes its OS
 * window, and the window's {@code ###} id keeps the size it was left at.
 * One resize at a time across all windows, since there is one mouse.
 * UI thread only, host context current.</p>
 */
public final class BorderResize {

    private static final String NONE = "";
    /** The window or one of its children has the mouse, whatever item is active. */
    private static final int HOVERED = ImGuiHoveredFlags.ChildWindows | ImGuiHoveredFlags.AllowWhenBlockedByActiveItem;

    /** The window being resized, by its ImGui name, or {@link #NONE}. */
    private String resized = NONE;
    /** The resize under way; null exactly when {@link #resized} is {@link #NONE}. */
    private ResizeDrag drag;

    /** No window is being resized. */
    public BorderResize() {
    }

    /**
     * Before {@code begin(name)}: when that window is being resized, gives it
     * the bounds that put the grabbed edge under the mouse, no smaller than
     * {@code minWidth} x {@code minHeight}; once the left button is up, the
     * resize is over.
     */
    public void beforeBegin(String name, int minWidth, int minHeight) {
        if (!resized.equals(name)) {
            return;
        }
        if (!ImGui.isMouseDown(ImGuiMouseButton.Left)) {
            resized = NONE;
            drag = null;
            return;
        }
        if (ImGui.isMousePosValid()) {
            WindowRect bounds = drag.rectAt(mouse(), minWidth, minHeight);
            ImGui.setNextWindowPos(bounds.x(), bounds.y(), ImGuiCond.Always);
            ImGui.setNextWindowSize(bounds.width(), bounds.height(), ImGuiCond.Always);
        }
    }

    /**
     * Inside window {@code name}, after {@code begin} and before its UI is
     * drawn: shows the resize cursor over {@code border}, starts a resize on a
     * left press there, and says whether the border has the pointer this frame.
     *
     * @return true while the mouse is in the band or the window is being
     *         resized: the UI must then be drawn with its pointer taken
     */
    public boolean takesPointer(String name, BorderHitTest border) {
        if (resized.equals(name)) {
            ImGui.setMouseCursor(drag.edge().mouseCursor());
            return true;
        }
        Optional<ResizeEdge> edge = edgeUnderMouse(border);
        if (edge.isEmpty()) {
            return false;
        }
        ImGui.setMouseCursor(edge.get().mouseCursor());
        if (ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            resized = name;
            drag = new ResizeDrag(windowBounds(), edge.get(), mouse());
        }
        return true;
    }

    /** True while {@code name} is being resized. */
    public boolean isResizing(String name) {
        return resized.equals(name);
    }

    /**
     * The edge the mouse is over in the current window's band, if it is free to
     * start a resize there. The window counts as hovered even while an item is
     * active: once the script's UI has keyboard focus, {@link HostFocus} keeps
     * the panel's own input item active every frame, which would otherwise hide
     * the band for good after the first press in the window. An item held by a
     * mouse button is still respected, through the held-button check.
     */
    private static Optional<ResizeEdge> edgeUnderMouse(BorderHitTest border) {
        boolean isHeldElsewhere = isAnyButtonDown() && !ImGui.isMouseClicked(ImGuiMouseButton.Left);
        if (isHeldElsewhere || !ImGui.isMousePosValid() || !ImGui.isWindowHovered(HOVERED)) {
            return Optional.empty();
        }
        return border.edgeAt(ImGui.getMousePosX() - ImGui.getWindowPosX(), ImGui.getMousePosY() - ImGui.getWindowPosY(),
                ImGui.getWindowWidth(), ImGui.getWindowHeight());
    }

    private static boolean isAnyButtonDown() {
        for (int button = 0; button < FrameInput.Pointer.buttonCount(); button++) {
            if (ImGui.isMouseDown(button)) {
                return true;
            }
        }
        return false;
    }

    private static WindowRect windowBounds() {
        return new WindowRect(Math.round(ImGui.getWindowPosX()), Math.round(ImGui.getWindowPosY()),
                Math.round(ImGui.getWindowWidth()), Math.round(ImGui.getWindowHeight()));
    }

    private static ScreenPoint mouse() {
        return new ScreenPoint(Math.round(ImGui.getMousePosX()), Math.round(ImGui.getMousePosY()));
    }
}
