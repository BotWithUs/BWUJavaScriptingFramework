package com.botwithus.bot.cli.gui.inspector;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.Platform;
import org.lwjgl.system.windows.User32;

/**
 * Whether a key is down as the desktop sees it, by Windows virtual-key code.
 * GLFW's key state is per window and updated only for the focused one, which
 * while an overlay is in use is the game's, so Windows is asked directly with
 * {@code GetAsyncKeyState}. Elsewhere GLFW is asked about {@code window}.
 */
final class DesktopKeys {

    /** The bit of {@code GetAsyncKeyState}'s result that means the key is down now. */
    private static final int KEY_DOWN_BIT = 0x8000;
    private static final boolean IS_WINDOWS = Platform.get() == Platform.WINDOWS;

    private DesktopKeys() {
    }

    static boolean isDown(int virtualKey, long window) {
        if (IS_WINDOWS) {
            return (User32.GetAsyncKeyState(virtualKey) & KEY_DOWN_BIT) != 0;
        }
        return switch (virtualKey) {
            case InteractKeys.VK_CONTROL -> isGlfwDown(window, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL);
            case InteractKeys.VK_SHIFT -> isGlfwDown(window, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT);
            case InteractKeys.VK_MENU -> isGlfwDown(window, GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT);
            default -> isGlfwDown(window, glfwKey(virtualKey), glfwKey(virtualKey));
        };
    }

    /** GLFW's code for a letter, digit or function key: letters and digits share Windows' codes. */
    private static int glfwKey(int virtualKey) {
        if (virtualKey >= InteractKeys.VK_F1 && virtualKey < InteractKeys.VK_F1 + InteractKeys.FUNCTION_KEYS) {
            return GLFW.GLFW_KEY_F1 + virtualKey - InteractKeys.VK_F1;
        }
        return virtualKey;
    }

    private static boolean isGlfwDown(long window, int left, int right) {
        return GLFW.glfwGetKey(window, left) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(window, right) == GLFW.GLFW_PRESS;
    }
}
