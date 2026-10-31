package com.botwithus.bot.core.voice;

/** How the user answered a microphone prompt. */
public enum ConsentAnswer {

    /** Allowed; remembered. */
    ALLOW,
    /** Denied; remembered. */
    DENY,
    /** Closed without choosing: refused for this run of the script only, and not remembered. */
    DISMISS
}
