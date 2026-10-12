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
 * @param isFocused the panel's window has keyboard focus
 */
record PanelInput(float originX, float originY, int itemId, boolean isOver, boolean isFocused) {

    private static final String INPUT_ID = "##script-ui-input";
    private static final int BUTTONS = ImGuiButtonFlags.MouseButtonLeft | ImGuiButtonFlags.MouseButtonRight
            | ImGuiButtonFlags.MouseButtonMiddle;
    /** Dear ImGui refuses a zero delta time; the host's first frame can report one. */
    private static final float MIN_DELTA_TIME = 1e-4f;

    /** Submits the panel's input item at the cursor, sized {@code width} x {@code height}. */
    static PanelInput submit(float width, float height) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImGui.invisibleButton(INPUT_ID, width, height, BUTTONS);
        boolean isHeld = ImGui.isItemActive() && isAnyButtonDown();
        return new PanelInput(x, y, ImGui.getItemID(), ImGui.isItemHovered() || isHeld, ImGui.isWindowFocused());
    }

    /** This frame's input for the sandbox, sized {@code width} x {@code height}. */
    FrameInput frame(float width, float height, TypedChars typed, StyleSnapshot style) {
        ImGuiIO io = ImGui.getIO();
        style.readFrom(ImGui.getStyle());
        return new FrameInput(width, height, Math.max(io.getDeltaTime(), MIN_DELTA_TIME), ImGui.getFont().ptr,
                io.getFontGlobalScale(), pointer(io), keyboard(io, typed), style);
    }

    private FrameInput.Pointer pointer(ImGuiIO io) {
        if (!isOver) {
            return FrameInput.Pointer.AWAY;
        }
        boolean[] buttons = new boolean[FrameInput.Pointer.buttonCount()];
        for (int button = 0; button < buttons.length; button++) {
            buttons[button] = io.getMouseDown(button);
        }
        return new FrameInput.Pointer(io.getMousePosX() - originX, io.getMousePosY() - originY, buttons,
                io.getMouseWheelH(), io.getMouseWheel());
    }

    private FrameInput.Keyboard keyboard(ImGuiIO io, TypedChars typed) {
        if (!isFocused) {
            return FrameInput.Keyboard.NONE;
        }
        BitSet down = new BitSet();
        for (int key = FrameInput.FIRST_KEY; key < FrameInput.END_KEY; key++) {
            down.set(key - FrameInput.FIRST_KEY, ImGui.isKeyDown(key));
        }
        int mods = (io.getKeyCtrl() ? ImGuiKey.ImGuiMod_Ctrl : 0) | (io.getKeyShift() ? ImGuiKey.ImGuiMod_Shift : 0)
                | (io.getKeyAlt() ? ImGuiKey.ImGuiMod_Alt : 0) | (io.getKeySuper() ? ImGuiKey.ImGuiMod_Super : 0);
        return new FrameInput.Keyboard(down, mods, typed.take());
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
