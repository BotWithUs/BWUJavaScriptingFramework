package com.botwithus.bot.cli.gui.loader;

/**
 * States of the loader/launcher screen state machine.
 */
public enum LoaderState {
    /** Showing login form, waiting for user credentials. */
    LOGIN,
    /** Validating credentials against the auth server. */
    AUTHENTICATING,
    /** Checking for and downloading updates. */
    UPDATING,
    /** Loading application resources (scripts, runtime). */
    LOADING,
    /** An error occurred — showing error UI with retry option. */
    ERROR,
    /** Loading complete — ready to transition to the main app. */
    COMPLETE
}
