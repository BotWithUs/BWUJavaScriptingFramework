package com.botwithus.bot.cli.gui.pages.connections;

/** The colour a status dot is drawn in: what the thing it marks needs from you. */
public enum Tone {
    /** Working. */
    OK,
    /** Worth a look. */
    WARN,
    /** Broken. */
    ERROR,
    /** Neither: stopped, closed or just a note. */
    NEUTRAL
}
