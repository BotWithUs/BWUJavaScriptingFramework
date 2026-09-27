package com.botwithus.bot.cli.settings;

/** Which view the host opens in ({@code ui.startMode}). F12 still switches at any time. */
public enum StartMode {
    /** Client cards only. */
    NORMAL,
    /** Client cards plus the sidebar pages. */
    ADVANCED
}
