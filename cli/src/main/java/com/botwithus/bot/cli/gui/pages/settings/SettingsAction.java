package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.gui.Icons;

/** A one-shot button on the Settings page. */
public enum SettingsAction {

    /** Clears RPC and loop timing on every client. */
    RESET_METRICS("Reset", Icons.ROTATE, true),
    /** Saves config, profiles and groups to one zip. */
    EXPORT_SETTINGS("Export", Icons.FILE_EXPORT, false);

    private final String button;
    private final String icon;
    private final boolean isDestructive;

    SettingsAction(String button, String icon, boolean isDestructive) {
        this.button = button;
        this.icon = icon;
        this.isDestructive = isDestructive;
    }

    /** The button's label. */
    public String button() {
        return button;
    }

    public String icon() {
        return icon;
    }

    /** Drawn in the danger style: it throws something away. */
    public boolean isDestructive() {
        return isDestructive;
    }
}
