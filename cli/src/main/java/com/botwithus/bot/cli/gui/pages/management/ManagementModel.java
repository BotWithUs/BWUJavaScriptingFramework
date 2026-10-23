package com.botwithus.bot.cli.gui.pages.management;

import com.botwithus.bot.cli.management.Target;

import java.util.Optional;

/**
 * What the Management page reads and asks for. The live model reads the
 * host's management runtime, targets, settings and audit log; the dev preview
 * supplies fixtures.
 *
 * <p>Every method runs on the render thread and returns promptly. Anything
 * that loads, starts, stops or restarts a script runs elsewhere and shows up
 * in a later {@link #view}. Asking about a script that has gone does nothing.</p>
 */
public interface ManagementModel {

    /** This frame's state. Cheap enough to call more than once a frame. */
    ManagementView view();

    /** Reloads the management folder; does nothing while a reload is running. */
    void reload();

    /** Shows the management folder in the system file browser. */
    void openFolder();

    /** Stops every running management script. Client scripts keep running. */
    void stopAll();

    /** Starts {@code script}, and keeps it running across host restarts. */
    void start(String script);

    /** Stops {@code script}, and keeps it stopped across host restarts. */
    void stop(String script);

    /** Stops and starts {@code script}, if it is running. */
    void restart(String script);

    /**
     * Adds or removes one of {@code script}'s targets. With {@code isRestart},
     * a running script is restarted afterwards so it starts over on its new
     * targets; the page asks the user before it passes {@code true}.
     */
    void changeTargets(String script, TargetChange change, boolean isRestart);

    /** Opens the shared inspector on {@code script}'s settings: one target's own, or the defaults when empty. */
    void openSettings(String script, Optional<Target> target);

    /** Opens the shared inspector on {@code script}'s own UI. */
    void openScriptUi(String script);
}
