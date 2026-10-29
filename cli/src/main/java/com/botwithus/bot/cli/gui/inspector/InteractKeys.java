package com.botwithus.bot.cli.gui.inspector;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;

/**
 * The keys held to make a pinned overlay take the mouse: one or more of Ctrl,
 * Shift and Alt, and at most one other key, all held at once. Written as
 * {@code Ctrl+Shift+Q}, modifiers first in that order.
 *
 * <p>Keys are Windows virtual-key codes, which for letters and digits are their
 * upper-case characters. Other keys held as well do not stop the combination.</p>
 *
 * @param isCtrl   Ctrl is part of it
 * @param isShift  Shift is part of it
 * @param isAlt    Alt is part of it
 * @param extraKey the one other key, as a virtual-key code from {@link #EXTRA_KEYS}, or {@link #NO_KEY}
 */
record InteractKeys(boolean isCtrl, boolean isShift, boolean isAlt, int extraKey) {

    static final int NO_KEY = 0;
    static final int VK_SHIFT = 0x10;
    static final int VK_CONTROL = 0x11;
    static final int VK_MENU = 0x12;
    static final int VK_ESCAPE = 0x1B;
    static final int VK_F1 = 0x70;
    static final int FUNCTION_KEYS = 12;
    /** The keys a combination may add to its modifiers: A to Z, 0 to 9 and F1 to F12. */
    static final List<Integer> EXTRA_KEYS = extraKeys();
    static final InteractKeys DEFAULT = new InteractKeys(true, false, false, NO_KEY);

    private static final String SEPARATOR = "+";
    private static final Pattern SPLIT = Pattern.compile(Pattern.quote(SEPARATOR));
    private static final String CTRL = "Ctrl";
    private static final String SHIFT = "Shift";
    private static final String ALT = "Alt";
    private static final String FUNCTION_PREFIX = "F";

    InteractKeys {
        if (!isCtrl && !isShift && !isAlt) {
            throw new IllegalArgumentException("needs Ctrl, Shift or Alt");
        }
        if (extraKey != NO_KEY && !EXTRA_KEYS.contains(extraKey)) {
            throw new IllegalArgumentException("not a key a combination can use: " + extraKey);
        }
    }

    private static List<Integer> extraKeys() {
        List<Integer> keys = new ArrayList<>();
        for (char c = 'A'; c <= 'Z'; c++) {
            keys.add((int) c);
        }
        for (char c = '0'; c <= '9'; c++) {
            keys.add((int) c);
        }
        for (int i = 0; i < FUNCTION_KEYS; i++) {
            keys.add(VK_F1 + i);
        }
        return List.copyOf(keys);
    }

    /** Reads {@code Ctrl+Shift+Q} and the like, in any case and order; empty when it is not a combination. */
    static Optional<InteractKeys> parse(String text) {
        boolean isCtrl = false;
        boolean isShift = false;
        boolean isAlt = false;
        int extra = NO_KEY;
        for (String part : SPLIT.split(text.strip(), -1)) {
            String name = part.strip();
            if (name.equalsIgnoreCase(CTRL)) {
                isCtrl = true;
            } else if (name.equalsIgnoreCase(SHIFT)) {
                isShift = true;
            } else if (name.equalsIgnoreCase(ALT)) {
                isAlt = true;
            } else {
                int key = keyNamed(name);
                if (key == NO_KEY || extra != NO_KEY) {
                    return Optional.empty();
                }
                extra = key;
            }
        }
        if (!isCtrl && !isShift && !isAlt) {
            return Optional.empty();
        }
        return Optional.of(new InteractKeys(isCtrl, isShift, isAlt, extra));
    }

    private static int keyNamed(String name) {
        for (int key : EXTRA_KEYS) {
            if (keyLabel(key).equalsIgnoreCase(name)) {
                return key;
            }
        }
        return NO_KEY;
    }

    /** How a key from {@link #EXTRA_KEYS} is written: its character, or F1 to F12. */
    static String keyLabel(int key) {
        if (key >= VK_F1 && key < VK_F1 + FUNCTION_KEYS) {
            return FUNCTION_PREFIX + (key - VK_F1 + 1);
        }
        return Character.toString(key);
    }

    /** The combination as written, e.g. {@code Ctrl+Shift+Q}. */
    String label() {
        List<String> parts = new ArrayList<>();
        if (isCtrl) {
            parts.add(CTRL);
        }
        if (isShift) {
            parts.add(SHIFT);
        }
        if (isAlt) {
            parts.add(ALT);
        }
        if (extraKey != NO_KEY) {
            parts.add(keyLabel(extraKey));
        }
        return String.join(SEPARATOR, parts);
    }

    /** Whether every key of the combination is down, as {@code isDown} reports virtual-key codes. */
    boolean isHeld(IntPredicate isDown) {
        return (!isCtrl || isDown.test(VK_CONTROL))
                && (!isShift || isDown.test(VK_SHIFT))
                && (!isAlt || isDown.test(VK_MENU))
                && (extraKey == NO_KEY || isDown.test(extraKey));
    }
}
