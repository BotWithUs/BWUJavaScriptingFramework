package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.core.alerts.AlertService;

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

    /** Scrolls the page's column down by {@code px}. */
    public static void scrollDown(SettingsPage page, float px) {
        page.scrollDown(px);
    }

    /** Types {@code text} into the service's secret box and presses Enter. */
    public static void typeSecret(SettingsPage page, AlertService service, String text) {
        page.typeSecret(service, text);
    }

    /** Presses the service's Show button. */
    public static void revealSecret(SettingsPage page, AlertService service) {
        page.revealSecret(service);
    }

    /** Types {@code text} into the named setting's box and presses Enter. */
    public static void type(SettingsPage page, String name, String text) {
        page.type(name, text);
    }
}
