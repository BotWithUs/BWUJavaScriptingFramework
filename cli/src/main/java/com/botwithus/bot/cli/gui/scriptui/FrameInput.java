package com.botwithus.bot.cli.gui.scriptui;

import imgui.ImFont;
import imgui.ImGuiIO;
import imgui.flag.ImGuiKey;

import java.util.BitSet;

/**
 * Everything a sandbox needs from the host for one frame: the panel's size,
 * the clock, the host's font and style, and whatever input is the panel's.
 * Captured on the UI thread with the host's context current, applied on the
 * sandbox's thread with the sandbox's context current. Positions are in the
 * panel's coordinates, where the sandbox's display starts at (0, 0).
 *
 * @param width       panel width in pixels, the sandbox's display width
 * @param height      panel height in pixels
 * @param deltaTime   seconds since the host's last frame, never zero
 * @param fontPtr     the host's current font, from the shared atlas
 * @param fontScale   the host's global font scale
 * @param pointer     the mouse, or {@link Pointer#away} when it is not the panel's
 * @param keyboard    the keyboard, or {@link Keyboard#NONE} when the panel lacks focus
 * @param style       the host's style at the panel
 */
record FrameInput(float width, float height, float deltaTime, long fontPtr, float fontScale,
                  Pointer pointer, Keyboard keyboard, StyleSnapshot style) {

    /** First key forwarded: {@code ImGuiKey_NamedKey_BEGIN}. */
    static final int FIRST_KEY = ImGuiKey.NamedKey_BEGIN;
    /** One past the last keyboard key; the gamepad and mouse pseudo-keys that follow are not forwarded. */
    static final int END_KEY = ImGuiKey.GamepadStart;

    /** Applies this input as the sandbox's next frame. */
    void applyTo(ImGuiIO io) {
        io.setDisplaySize(width, height);
        io.setDeltaTime(deltaTime);
        io.setFontGlobalScale(fontScale);
        if (fontPtr != 0L) {
            io.setFontDefault(new ImFont(fontPtr));
        }
        pointer.applyTo(io);
        keyboard.applyTo(io);
    }

    /**
     * The mouse as the panel sees it.
     *
     * @param x       position relative to the panel
     * @param y       position relative to the panel
     * @param buttons which buttons are down, indexed by {@code ImGuiMouseButton}
     * @param wheelX  horizontal wheel this frame
     * @param wheelY  vertical wheel this frame
     */
    record Pointer(float x, float y, boolean[] buttons, float wheelX, float wheelY) {

        /** Dear ImGui's "no mouse" position. */
        private static final float NO_POSITION = -Float.MAX_VALUE;
        private static final int BUTTONS = 5;

        /** The mouse is elsewhere: no position, every button up. */
        static final Pointer AWAY = away(new boolean[BUTTONS]);

        Pointer {
            buttons = buttons.clone();
        }

        /** The mouse is elsewhere, with {@code buttons} down: no position, and no wheel. */
        static Pointer away(boolean[] buttons) {
            return new Pointer(NO_POSITION, NO_POSITION, buttons, 0f, 0f);
        }

        /** How many buttons Dear ImGui tracks ({@code ImGuiMouseButton_COUNT}). */
        static int buttonCount() {
            return BUTTONS;
        }

        void applyTo(ImGuiIO io) {
            io.addMousePosEvent(x, y);
            for (int button = 0; button < buttons.length; button++) {
                io.addMouseButtonEvent(button, buttons[button]);
            }
            if (wheelX != 0f || wheelY != 0f) {
                io.addMouseWheelEvent(wheelX, wheelY);
            }
        }
    }

    /**
     * The keyboard as the panel sees it. Key and modifier state is sent every
     * frame (Dear ImGui drops events that change nothing), so a key held when
     * the panel loses focus reads as released.
     *
     * @param keysDown bit {@code key - FIRST_KEY} set for each keyboard key down
     * @param mods     {@code ImGuiKey.ImGuiMod_*} bits down
     * @param chars    characters typed this frame, as code points
     * @param isFocusLost the panel lost keyboard focus since the last frame:
     *                 nothing in the sandbox keeps it either
     */
    record Keyboard(BitSet keysDown, int mods, int[] chars, boolean isFocusLost) {

        private static final int[] MODS = {
            ImGuiKey.ImGuiMod_Ctrl, ImGuiKey.ImGuiMod_Shift, ImGuiKey.ImGuiMod_Alt, ImGuiKey.ImGuiMod_Super,
        };

        /** Nothing down, nothing typed. */
        static final Keyboard NONE = new Keyboard(new BitSet(), 0, new int[0], false);
        /** {@link #NONE}, on the frame the panel loses keyboard focus. */
        static final Keyboard FOCUS_LOST = new Keyboard(new BitSet(), 0, new int[0], true);

        Keyboard {
            BitSet copy = new BitSet();
            copy.or(keysDown);
            keysDown = copy;
            chars = chars.clone();
        }

        void applyTo(ImGuiIO io) {
            for (int key = FIRST_KEY; key < END_KEY; key++) {
                io.addKeyEvent(key, keysDown.get(key - FIRST_KEY));
            }
            for (int mod : MODS) {
                io.addKeyEvent(mod, (mods & mod) != 0);
            }
            for (int c : chars) {
                io.addInputCharacter(c);
            }
        }
    }
}
