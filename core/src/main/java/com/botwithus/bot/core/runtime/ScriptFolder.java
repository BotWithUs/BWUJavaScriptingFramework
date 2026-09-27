package com.botwithus.bot.core.runtime;

/**
 * The two folders the host loads script JARs from. A load pass, a failed-load
 * entry and a folder-watch event each belong to exactly one of them.
 */
public enum ScriptFolder {

    /** {@code scripts/}: bot scripts, loaded once per connected client. */
    SCRIPTS,

    /** {@code scripts/management/}: management scripts, loaded once for the host. */
    MANAGEMENT
}
