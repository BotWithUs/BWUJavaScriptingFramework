package com.botwithus.bot.cli.settings;

/**
 * What quiet hours do with an alert they hold back ({@code alerts.quiet.mode}).
 * Crashes are never held back, and the daily summary goes out at its time either way.
 */
public enum QuietMode {
    /** Keep it, and send what was kept as one message per service when quiet hours end. */
    HOLD,
    /** Drop it. It is not sent later. */
    MUTE
}
