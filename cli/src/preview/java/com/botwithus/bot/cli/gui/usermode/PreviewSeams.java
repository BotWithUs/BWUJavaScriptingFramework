package com.botwithus.bot.cli.gui.usermode;

import imgui.type.ImInt;

/**
 * The dev preview's reach into package-private Normal-mode state: selecting a
 * view segment and staging an edit in the inspector, which a real user does by
 * clicking. Lives in the preview source set only; nothing here ships.
 */
public final class PreviewSeams {

    private PreviewSeams() {}

    public static void showNeedsAttention(UserModeRenderer page) {
        page.showView(ClientFilter.View.NEEDS_ATTENTION);
    }

    /**
     * Sets an int-backed field (int, item id, or choice index) in the open
     * inspector. Returns false until the inspector has drawn its first frame.
     */
    public static boolean stageEdit(UserModeRenderer page, String key, int value) {
        ConfigEdits edits = page.inspector().edits();
        if (edits == null) {
            return false;
        }
        ImInt field = edits.intOf(key);
        if (field == null) {
            return false;
        }
        field.set(value);
        return true;
    }
}
