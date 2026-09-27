package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.cli.events.ClientKey;

/**
 * Everything a Normal-mode control can ask the host to do. Each method takes the
 * card's {@link ClientView#id()} and is a no-op if the host no longer knows that
 * client, or it has no connection for a script action. None blocks the caller,
 * which is the render thread.
 */
public interface ClientActions {

    void startScript(ClientKey client, ScriptEntry script);

    /**
     * Starts the subscribed script with catalogue id {@code scriptId}. An installed
     * copy starts at once; otherwise it is installed through the launcher first and
     * started when it lands, while {@link ClientBoard#subscriptions} reports progress.
     */
    void startSubscription(ClientKey client, String scriptId);

    /** Stops the script named {@code scriptName} on the client. */
    void stopScript(ClientKey client, String scriptName);

    /** Starts the script named {@code scriptName} again: one that was stopped, or one that crashed. */
    void runScript(ClientKey client, String scriptName);

    /** Rebuilds a lost connection from scratch. */
    void reconnect(ClientKey client);

    /**
     * Retries at once: cuts a reconnect back-off short, or starts recovering again
     * after the client gave up. Rebuilds the connection when there is nothing to
     * retry, and does nothing once the client's process has exited.
     */
    void retryNow(ClientKey client);

    /** Stops retrying; the client stays not responding until retried. */
    void stopRetrying(ClientKey client);

    /** Drops a client that has gone, and everything the host remembered about it. */
    void forget(ClientKey client);

    /** Shows the log for this client (Advanced → Logs). */
    void viewLog(ClientKey client);

    /** Turns "Resume after restart" on or off for the client's account. */
    void setResumeAfterRestart(ClientKey client, boolean isOn);
}
