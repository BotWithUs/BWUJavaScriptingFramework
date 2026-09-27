package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.cli.gui.pages.installed.InstalledState.DetailTab;

import java.nio.file.Path;

/**
 * The dev preview's reach into the Installed scripts page, standing in for the
 * clicks a user would make: pick a row and tab, filter, open Start on… and tick
 * clients, open a stack trace, arm Stop everywhere. Lives in the preview source
 * set only; nothing here ships.
 */
public final class InstalledPreviewSeams {

    private InstalledPreviewSeams() {}

    public static void selectClientsTab(InstalledPage page, String key) {
        page.select(key, DetailTab.CLIENTS);
    }

    public static void selectAboutTab(InstalledPage page, String key) {
        page.select(key, DetailTab.ABOUT);
    }

    public static void showProblemsOnly(InstalledPage page) {
        page.showQuery(InstalledQuery.DEFAULT.withShow(InstalledQuery.Show.PROBLEMS));
    }

    public static void openStartOn(InstalledPage page, String key) {
        page.openStartOn(key);
    }

    public static void tick(InstalledPage page, String clientId) {
        page.tick(clientId);
    }

    public static void openTrace(InstalledPage page, Path jar) {
        page.openTrace(jar);
    }

    public static void armStop(InstalledPage page, String key) {
        page.armStop(key);
    }
}
