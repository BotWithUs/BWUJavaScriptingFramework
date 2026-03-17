package com.botwithus.bot.cli.gui;

import imgui.ImGui;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

/**
 * Shared clipboard utilities for GUI panels.
 */
final class ClipboardHelper {

    static final float FEEDBACK_DURATION = 1.5f;

    private ClipboardHelper() {}

    /** Copy text to the system clipboard. Silently ignored in headless environments. */
    static void copyToClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
        } catch (Exception ignored) {
            // Clipboard may be unavailable in headless environments
        }
    }

    /**
     * Render copy-feedback UI: shows "Copied!" while timer &gt; 0, otherwise renders nothing.
     * @return the updated timer value (caller should store it back)
     */
    static float renderCopyFeedback(float timer) {
        if (timer > 0f) {
            ImGui.textColored(ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B, 1f, "Copied!");
            return timer - ImGui.getIO().getDeltaTime();
        }
        return 0f;
    }
}
