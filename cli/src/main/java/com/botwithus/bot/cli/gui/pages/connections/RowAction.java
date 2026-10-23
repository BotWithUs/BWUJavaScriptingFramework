package com.botwithus.bot.cli.gui.pages.connections;

/** Everything a Connections row can offer. Which ones it offers depends on its {@link LinkState}. */
public enum RowAction {

    /** Connect to a found pipe. */
    CONNECT,
    /** Drop the connection and stop its scripts. */
    DISCONNECT,
    /** Retry a dropped pipe at once, or start retrying again after a stop. */
    RETRY_NOW,
    /** Stop retrying a dropped pipe. */
    STOP_RETRYING,
    /** Drop a gone client and everything the host remembered about it. */
    FORGET,
    /** Make this connection the one console commands run on. */
    CONSOLE_TARGET,
    /** Show only this connection's output in the console, or everyone's again. */
    OUTPUT_FILTER
}
