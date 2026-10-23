package com.botwithus.bot.cli.gui.pages.installed;

import java.util.List;

/**
 * What the Installed scripts page reads and asks for. The live model reads the
 * host's load report, runtimes, failed-load list and Store ledger; the dev
 * preview supplies fixtures.
 *
 * <p>Every method runs on the render thread and returns promptly. Anything that
 * stops, starts or loads scripts is queued elsewhere and shows up in a later
 * {@link #view}.</p>
 */
public interface InstalledModel {

    /** This frame's state. Cheap enough to call more than once a frame. */
    InstalledView view();

    /** Starts or stops watching the scripts folder for changed JARs. */
    void setWatching(boolean isWatching);

    /** Whether a reload starts again the scripts that were running before it. */
    void setRestartAfterReload(boolean isRestartAfterReload);

    /** Reloads the scripts folder on every client; does nothing while a reload is running. */
    void reload();

    /** Shows the scripts folder in the system file browser. */
    void openFolder();

    /** Starts {@code key}'s script on each of {@code clientIds} that can take it. */
    void startOn(String key, List<String> clientIds);

    /** Stops {@code key}'s script on every client running it. */
    void stopEverywhere(String key);

    /** Stops {@code key}'s script on one client. */
    void stop(String key, String clientId);

    /** Starts {@code key}'s script on one client, or restarts it there after a crash. */
    void run(String key, String clientId);

    /** Opens the shared config inspector on {@code key}'s script on one client. */
    void openSettings(String key, String clientId);

    /** Opens "Report a problem" for {@code key}'s script on one client. */
    void reportProblem(String key, String clientId);
}
