package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.settings.StartMode;

/**
 * Application display mode — determines which UI layout is rendered.
 */
public enum AppMode {
    /** Simplified card-based dashboard for monitoring connected clients. */
    NORMAL,
    /** Full panel-based UI with console, logs, settings, and developer tools. */
    ADVANCED;

    /** The mode the host opens in for the {@code ui.startMode} setting. */
    public static AppMode openingIn(StartMode start) {
        return switch (start) {
            case NORMAL -> NORMAL;
            case ADVANCED -> ADVANCED;
        };
    }
}
