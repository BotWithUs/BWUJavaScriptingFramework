package com.botwithus.bot.cli.gui;

/**
 * Application display mode — determines which UI layout is rendered.
 */
public enum AppMode {
    /** Account management: add, launch, and manage game accounts. */
    LAUNCHER,
    /** Simplified card-based dashboard for monitoring connected clients. */
    NORMAL,
    /** Full panel-based UI with console, logs, settings, and developer tools. */
    ADVANCED
}
