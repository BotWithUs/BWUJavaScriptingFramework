package com.botwithus.bot.cli.gui.scriptui;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiButtonFlags;
import imgui.flag.ImGuiKey;

import java.util.BitSet;

/**
 * The host's side of a script UI panel: one invisible button covering it, so
 * the host treats a click there as its own (no window drag, no click-through),
 * and the input that belongs to the panel, read from the host's context.
 * UI thread, host context current, inside the panel's child window.
 *
 * @param originX screen position of the panel's top-left corner
 * @param originY screen position of the panel's top-left corner
 * @param itemId  the invisible button's id, for {@link HostFocus}
 * @param isOver  the mouse is the panel's: hovering it, or holding a press that started on it
 * @param isPressed the input item is active from a press of its own this frame
 * @param isPressElsewhere a mouse button went down this frame and the panel is not
 *                  hovered: the press is the host's, on a host item or window
 * @param isFocused the panel's window has keyboard focus
 */
record PanelInput(float originX, float originY, int itemId, boolean isOver, boolean isPressed,
                  boolean isPressElsewhere, boolean isFocused) {

    private static final String INPUT_ID = "##script-ui-input";
    private static final int BUTTONS = ImGuiButtonFlags.MouseButtonLeft | ImGuiButtonFlags.MouseButtonRight
            | ImGuiButtonFlags.MouseButtonMiddle;
    /** Dear ImGui refuses a zero delta time; the host's first frame can report one. */
    private static final float MIN_DELTA_TIME = 1e-4f;

    /**
     * Submits the panel's input item at the cursor, sized {@code width} x {@code height}.
     *
     * @param isPointerTaken the host has the mouse this frame, so the panel is never {@link #isOver}
     */
    static PanelInput submit(float width, float height, boolean isPointerTaken) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImGui.invisibleButton(INPUT_ID, width, height, BUTTONS);
        boolean isHovered = ImGui.isItemHovered();
        boolean isHeld = ImGui.isItemActive() && isAnyButtonDown();
        boolean isOver = !isPointerTaken && (isHovered || isHeld);
        return new PanelInput(x, y, ImGui.getItemID(), isOver, isHeld,
                isAnyButtonClicked() && !isHovered, ImGui.isWindowFocused());
    }

    /**
     * This frame's input for the sandbox, sized {@code width} x {@code height}.
     *
     * @param isFocusLost the panel had keyboard focus last frame and has lost it:
     *        the sandbox drops its own focus too
     */
    FrameInput frame(float width, float height, TypedChars typed, StyleSnapshot style, boolean isFocusLost) {
        ImGuiIO io = ImGui.getIO();
        return input(io, width, height, pointer(io), keyboard(io, typed, isFocusLost), style);
    }

    /**
     * Input for a sandboxed frame that no panel shows: the host's clock, font
     * and style, but no pointer and no keyboard, so nothing typed is consumed.
     */
    static FrameInput unseen(float width, float height, StyleSnapshot style) {
        return input(ImGui.getIO(), width, height, FrameInput.Pointer.AWAY, FrameInput.Keyboard.NONE, style);
    }

    private static FrameInput input(ImGuiIO io, float width, float height, FrameInput.Pointer pointer,
                                    FrameInput.Keyboard keyboard, StyleSnapshot style) {
        style.readFrom(ImGui.getStyle());
        return new FrameInput(width, height, Math.max(io.getDeltaTime(), MIN_DELTA_TIME), ImGui.getFont().ptr,
                io.getFontGlobalScale(), pointer, keyboard, style);
    }

    /**
     * The mouse over the panel, or, when it is elsewhere, no position but the
     * buttons as they are: a press elsewhere in the host is then a press on
     * nothing to the script's UI, which ends its typing and closes its popups,
     * as a press elsewhere did before script UIs had a context of their own.
     */
    private FrameInput.Pointer pointer(ImGuiIO io) {
        boolean[] buttons = new boolean[FrameInput.Pointer.buttonCount()];
        for (int button = 0; button < buttons.length; button++) {
            buttons[button] = io.getMouseDown(button);
        }
        if (!isOver) {
            return FrameInput.Pointer.away(buttons);
        }
        return new FrameInput.Pointer(io.getMousePosX() - originX, io.getMousePosY() - originY, buttons,
                io.getMouseWheelH(), io.getMouseWheel());
    }

    private FrameInput.Keyboard keyboard(ImGuiIO io, TypedChars typed, boolean isFocusLost) {
        if (!isFocused) {
            return isFocusLost ? FrameInput.Keyboard.FOCUS_LOST : FrameInput.Keyboard.NONE;
        }
        BitSet down = new BitSet();
        for (int key = FrameInput.FIRST_KEY; key < FrameInput.END_KEY; key++) {
            down.set(key - FrameInput.FIRST_KEY, ImGui.isKeyDown(key));
        }
        int mods = (io.getKeyCtrl() ? ImGuiKey.ImGuiMod_Ctrl : 0) | (io.getKeyShift() ? ImGuiKey.ImGuiMod_Shift : 0)
                | (io.getKeyAlt() ? ImGuiKey.ImGuiMod_Alt : 0) | (io.getKeySuper() ? ImGuiKey.ImGuiMod_Super : 0);
        return new FrameInput.Keyboard(down, mods, typed.take(), false);
    }

    private static boolean isAnyButtonClicked() {
        for (int button = 0; button < FrameInput.Pointer.buttonCount(); button++) {
            if (ImGui.isMouseClicked(button)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAnyButtonDown() {
        for (int button = 0; button < FrameInput.Pointer.buttonCount(); button++) {
            if (ImGui.isMouseDown(button)) {
                return true;
            }
        }
        return false;
    }
}
