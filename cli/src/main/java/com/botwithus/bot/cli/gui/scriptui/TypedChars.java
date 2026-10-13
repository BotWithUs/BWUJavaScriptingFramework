package com.botwithus.bot.cli.gui.scriptui;

import java.util.Arrays;

/**
 * Characters typed since the host's last frame. A script UI's sandbox has its
 * own context, so it never sees the host's text input; GLFW's char callback
 * feeds this as well as the host, and the panel that has keyboard focus takes
 * them. Whatever no panel takes is dropped at the end of the frame. UI thread
 * only (GLFW calls back from {@code glfwPollEvents}).
 */
public final class TypedChars {

    private static final int INITIAL_CAPACITY = 16;

    private int[] chars = new int[INITIAL_CAPACITY];
    private int count;

    /** Empty: nothing typed yet. */
    public TypedChars() {
    }

    /** A character GLFW reported, as a Unicode code point. */
    public void typed(int codepoint) {
        if (count == chars.length) {
            chars = Arrays.copyOf(chars, count * 2);
        }
        chars[count++] = codepoint;
    }

    /** Everything typed since the last {@link #take} or {@link #clear}, and forgets it. */
    int[] take() {
        int[] taken = Arrays.copyOf(chars, count);
        count = 0;
        return taken;
    }

    void clear() {
        count = 0;
    }
}
