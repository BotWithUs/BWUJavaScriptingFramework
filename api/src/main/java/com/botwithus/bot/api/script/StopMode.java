package com.botwithus.bot.api.script;

/** How {@link ClientLauncher#stop(String, StopMode)} ends a client. */
public enum StopMode {
    /** Ask the game to close, as the window's close button does. */
    GRACEFUL,
    /** Terminate the process at once. */
    KILL
}
