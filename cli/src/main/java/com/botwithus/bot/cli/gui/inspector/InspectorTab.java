package com.botwithus.bot.cli.gui.inspector;

/** The inspector's two tabs. */
public enum InspectorTab {

    /** The script's declared fields, applied together. */
    SETTINGS,

    /** The script's own ImGui, framed but not restyled. Offered only when the script has one. */
    SCRIPT_UI;

    /**
     * The tab a "Settings" button opens on: the fields when there are any, else
     * the script's own UI. A script that ships both opens on its fields, so a
     * custom UI never hides the settings; one with a UI and no fields would
     * otherwise open on an empty form.
     */
    public static InspectorTab initialFor(boolean hasFields, boolean hasCustomUi) {
        return !hasFields && hasCustomUi ? SCRIPT_UI : SETTINGS;
    }
}
