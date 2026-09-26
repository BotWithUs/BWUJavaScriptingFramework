package com.botwithus.bot.cli.gui.usermode.board;

/**
 * Everything a Normal-mode control can ask the host to do. Each method takes the
 * card's {@link ClientView#id()} and is a no-op if that client has gone.
 */
public interface ClientActions {

    void startScript(String clientId, ScriptEntry script);

    /**
     * Starts the subscribed script with catalogue id {@code scriptId}. An installed
     * copy starts at once; otherwise it is installed through the launcher first and
     * started when it lands, while {@link ClientBoard#subscriptions} reports progress.
     */
    void startSubscription(String clientId, String scriptId);

    void stopScript(String clientId);

    /** Starts the crashed script again. */
    void restartScript(String clientId);

    /** Rebuilds a lost connection from scratch. */
    void reconnect(String clientId);

    /** Stops retrying; the card drops to "lost contact". */
    void cancelReconnect(String clientId);

    /** Shows the log for this client (Advanced → Logs). */
    void viewLog(String clientId);

    /** Retries every client that gave up. */
    void retryHost();
}
