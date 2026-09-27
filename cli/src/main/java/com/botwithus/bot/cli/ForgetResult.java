package com.botwithus.bot.cli;

/** What {@link CliContext#forget(String)} did. */
public enum ForgetResult {
    /** The client is gone from the host, along with everything it remembered about it. */
    FORGOTTEN,
    /** The client's pipe is still open; disconnect it first. */
    STILL_CONNECTED,
    /** The host has no record of that client. */
    NOT_FOUND
}
