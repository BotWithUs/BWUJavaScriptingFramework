package com.botwithus.bot.cli.gui.pages.settings;

/** A file or folder the Settings page can open in Explorer or the default editor. */
public enum SettingsPlace {
    /** {@code ~/.botwithus}: config, profiles, groups and logs. */
    DATA_FOLDER,
    /** The folder script JARs are loaded from. */
    SCRIPTS_FOLDER,
    /** {@code config.properties} itself. */
    CONFIG_FILE
}
