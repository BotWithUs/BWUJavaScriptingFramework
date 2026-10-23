package com.botwithus.bot.cli.gui.inspector;

/**
 * The inspector's two tabs. Which one a "Settings" button opens is
 * {@link InspectorRequest#settings}'s choice.
 */
public enum InspectorTab {

    /** The script's declared fields, applied together. */
    SETTINGS,

    /**
     * The script's own ImGui, framed but not restyled, or a way back to it while
     * it is popped out into a window of its own. Offered only when the script has one.
     */
    SCRIPT_UI
}
