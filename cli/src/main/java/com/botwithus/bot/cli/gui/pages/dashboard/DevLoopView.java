package com.botwithus.bot.cli.gui.pages.dashboard;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The dev loop card: where scripts load from, what the last load found, and the
 * two switches that decide what happens when a JAR changes.
 *
 * @param scriptsFolder        the folder scripts load from, as the sidebar shows it
 * @param isWatchOn            the Watch setting
 * @param isWatcherRunning     whether a folder watch is actually running; the setting alone does not prove it
 * @param isRestartAfterReload the Restart-after-reload setting
 * @param lastReload           when the host last saw a load pass finish, if it has seen one
 * @param scriptsLoaded        scripts the last pass found
 * @param jarsLoaded           JARs that loaded in the last pass
 * @param jarsFailed           JARs that failed in the last pass
 * @param clients              live clients a reload reaches
 * @param isReloading          a reload started from here is still running
 */
public record DevLoopView(String scriptsFolder, boolean isWatchOn, boolean isWatcherRunning,
                          boolean isRestartAfterReload, Optional<Instant> lastReload, int scriptsLoaded,
                          int jarsLoaded, int jarsFailed, int clients, boolean isReloading) {

    public DevLoopView {
        Objects.requireNonNull(scriptsFolder, "scriptsFolder");
        Objects.requireNonNull(lastReload, "lastReload");
    }
}
