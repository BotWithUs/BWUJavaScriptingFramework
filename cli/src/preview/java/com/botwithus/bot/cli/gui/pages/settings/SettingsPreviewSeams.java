package com.botwithus.bot.cli.gui.pages.settings;

/**
 * DEV ONLY. The preview's reach into the Settings page's package-private
 * state, to do what a user does by typing and clicking. Nothing here ships.
 */
public final class SettingsPreviewSeams {

    private SettingsPreviewSeams() {
    }

    public static void find(SettingsPage page, String text) {
        page.find(text);
    }

    public static void showSection(SettingsPage page, SettingsSection section) {
        page.showSection(section);
    }

    /** Types {@code text} into the named setting's box and presses Enter. */
    public static void type(SettingsPage page, String name, String text) {
        page.type(name, text);
    }
}
