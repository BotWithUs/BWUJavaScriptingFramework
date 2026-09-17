package com.botwithus.bot.cli.gui;

import imgui.ImGui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

/**
 * Shared clipboard utilities for GUI panels.
 */
final class ClipboardHelper {

    private static final Logger log = LoggerFactory.getLogger(ClipboardHelper.class);

    static final float FEEDBACK_DURATION = 1.5f;

    private ClipboardHelper() {}

    /** Copy text to the system clipboard. Silently ignored in headless environments. */
    static void copyToClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
        } catch (Exception e) {
            // Clipboard may be unavailable in headless environments
            log.debug("Clipboard unavailable: {}", e.getMessage());
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
