package com.botwithus.bot.cli.gui.scriptui;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiMouseButton;

/**
 * Moves a frameless window that holds a script's UI when the user drags its
 * background. Dear ImGui cannot do this itself: the panel a sandboxed UI is
 * composited into is covered by one input item, so to the host every press
 * there lands on an item. {@link ScriptUiRenderer#draw} instead reports a press
 * that landed on none of the script's own widgets, and this class takes it from
 * there: the window follows the mouse until the left button is released.
 *
 * <p>One drag at a time across all windows, since there is one mouse. The
 * window is placed with {@code setNextWindowPos}, so with viewports on, Dear
 * ImGui moves its OS window. UI thread only, host context current.</p>
 */
public final class BackgroundDrag {

    private static final String NONE = "";

    /** The window being dragged, by its ImGui name, or {@link #NONE}. */
    private String dragged = NONE;
    /** Where in the window the press landed, so the window keeps that point under the mouse. */
    private float grabX;
    private float grabY;

    /** No window is being dragged. */
    public BackgroundDrag() {
    }

    /**
     * Before {@code begin(name)}: when that window is being dragged, places it
     * under the mouse; once the left button is up, the drag is over.
     */
    public void beforeBegin(String name) {
        if (!dragged.equals(name)) {
            return;
        }
        if (!ImGui.isMouseDown(ImGuiMouseButton.Left)) {
            dragged = NONE;
            return;
        }
        if (ImGui.isMousePosValid()) {
            ImGui.setNextWindowPos(ImGui.getMousePosX() - grabX, ImGui.getMousePosY() - grabY, ImGuiCond.Always);
        }
    }

    /**
     * Inside window {@code name}, after its UI is drawn: a press on the UI's
     * background starts dragging the window.
     */
    public void afterDraw(String name, boolean isBackgroundPress) {
        if (!isBackgroundPress) {
            return;
        }
        dragged = name;
        grabX = ImGui.getMousePosX() - ImGui.getWindowPosX();
        grabY = ImGui.getMousePosY() - ImGui.getWindowPosY();
    }

    /** True while {@code name} is being dragged. */
    public boolean isDragging(String name) {
        return dragged.equals(name);
    }
}
