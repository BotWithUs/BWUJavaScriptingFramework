package com.botwithus.bot.api.domain;

import com.botwithus.bot.api.input.FocusMode;
import com.botwithus.bot.api.input.FocusState;
import com.botwithus.bot.api.input.KeyResult;

/**
 * Real key presses posted to the client's render view, and the focus mode that
 * decides whether a background client accepts them.
 *
 * <p>A key sent here reaches the client the way a keyboard press does, so it
 * fires whatever the player has bound to that key (an action bar slot's
 * keybind, for example). It is a different mechanism from
 * {@link com.botwithus.bot.api.GameAPI#fireKeys}, which runs a component's CS2
 * key trigger and never reaches a keybind.</p>
 *
 * <h2>Focus</h2>
 * <p>The client drops keys while its render view does not hold the keyboard,
 * so the agent refuses to send one then and {@link #sendKey(int, int)} answers
 * {@link KeyResult#NOT_FOCUSED}. With {@link FocusMode#REAL} (the default) a
 * key is only delivered while the client really has focus. Arm
 * {@link FocusMode#AGENT} with {@link #setFocusMode(FocusMode)} to send keys to
 * a background client, and set {@link FocusMode#REAL} back when done.</p>
 *
 * <h2>One key at a time</h2>
 * <p>The agent serialises presses, so only one key is ever down: there are no
 * chords and no modifier combinations (Shift plus a key). A call that holds a
 * key blocks every other key press on the same client until it returns.</p>
 *
 * @see com.botwithus.bot.api.GameAPI
 */
public interface KeyboardAPI {

    /** Largest Win32 virtual-key code {@link #sendKey(int, int)} accepts. */
    int MAX_VIRTUAL_KEY = 255;

    /** Longest hold {@link #sendKey(int, int)} accepts, in milliseconds. */
    int MAX_HOLD_MS = 5000;

    /**
     * Presses and releases one key with no hold. Same as
     * {@code sendKey(virtualKey, 0)}.
     *
     * <p>Needs the render view to hold the keyboard: the client really focused,
     * or {@link FocusMode#AGENT} armed. One key is down at a time; there are no
     * chords.</p>
     *
     * @param virtualKey Win32 virtual-key code, {@code 0} to {@link #MAX_VIRTUAL_KEY}
     * @return what became of the press; only {@link KeyResult#SENT} means it was delivered
     * @throws IllegalArgumentException if {@code virtualKey} is out of range
     */
    KeyResult sendKey(int virtualKey);

    /**
     * Presses one key, holds it for {@code holdMs}, and releases it. Blocks for
     * the hold. The agent decides from the key code whether it is an extended
     * key (arrows, navigation keys, right-hand modifiers).
     *
     * <p>Needs the render view to hold the keyboard: the client really focused,
     * or {@link FocusMode#AGENT} armed. A focus refusal is a result, not an
     * exception: {@link KeyResult#NOT_FOCUSED}, {@link KeyResult#NO_WINDOW}, or
     * {@link KeyResult#FOCUS_LOST} when focus left during the hold. One key is
     * down at a time; there are no chords, and a hold blocks every other press
     * on this client until it ends.</p>
     *
     * @param virtualKey Win32 virtual-key code, {@code 0} to {@link #MAX_VIRTUAL_KEY}
     * @param holdMs     how long to hold the key down, {@code 0} to {@link #MAX_HOLD_MS}
     * @return what became of the press; only {@link KeyResult#SENT} means it was delivered
     * @throws IllegalArgumentException if {@code virtualKey} or {@code holdMs} is out of range
     */
    KeyResult sendKey(int virtualKey, int holdMs);

    /**
     * Switches the client's focus mode and returns the state read back after
     * the call.
     *
     * <p>Neither failure the agent can report throws:</p>
     * <ul>
     *   <li>{@link FocusMode#AGENT} on a client where it cannot be armed returns
     *       the current state, whose {@link FocusState#available()} is
     *       {@code false}. {@link FocusMode#REAL} keeps working there.</li>
     *   <li>A switch the agent could not complete returns the state read back
     *       afterwards, which may still be the old mode. Compare its
     *       {@link FocusState#mode()} with the one asked for and retry if it
     *       differs.</li>
     * </ul>
     *
     * <p>Switching back to {@link FocusMode#REAL} waits for any held key to be
     * released first, so it can block for up to the length of a hold. This call
     * presses no key and synthesises no input; key presses still need focus as
     * described on {@link #sendKey(int, int)}, one key at a time.</p>
     *
     * @param mode the mode to switch to
     * @return the state after the call
     * @throws NullPointerException if {@code mode} is {@code null}
     */
    FocusState setFocusMode(FocusMode mode);

    /**
     * The client's focus mode right now, and whether its render view really
     * holds the keyboard. A key sent while this reports {@link FocusMode#REAL}
     * with {@link FocusState#realFocusOnView()} {@code false} is refused as
     * {@link KeyResult#NOT_FOCUSED}. One key is down at a time; there are no
     * chords.
     *
     * @return the current focus state
     */
    FocusState focusMode();
}
