package com.botwithus.bot.cli.gui.pages.connections;

import java.util.Optional;

/**
 * What the Connections page reads and asks for. The live model reads the host's
 * client registry, connections and settings; the dev preview supplies fixtures.
 *
 * <p>Every method runs on the render thread and returns promptly. Anything that
 * touches a pipe, stops scripts or writes a file happens elsewhere and shows up
 * in a later {@link #view()}. An action on a row that no longer offers it, or
 * whose client the host no longer knows, does nothing.</p>
 */
public interface ConnectionsModel {

    /** This frame's rows and header state. */
    ConnectionsView view();

    /**
     * How many clients are reconnecting or stopped: the sidebar's warning count.
     * Read every frame the sidebar is drawn, page shown or not, so it must be cheap.
     */
    int notResponding();

    /** The detail pane's extras for {@code row}. Called for the selected row only. */
    ConnectionDetail detail(ConnectionRow row);

    /** Looks for pipes under the prefix now; does nothing while a scan is running. */
    void scan();

    /** Connects to a found pipe. */
    void connect(ConnectionRow row);

    /** Connects to every found pipe, one after another. */
    void connectAllFound();

    /** Drops the connection, stopping its scripts. */
    void disconnect(ConnectionRow row);

    void retryNow(ConnectionRow row);

    void stopRetrying(ConnectionRow row);

    /** Drops a gone client and what the host remembered about it; its saved scripts stay. */
    void forget(ConnectionRow row);

    /** Makes console commands run on this connection. */
    void setConsoleTarget(ConnectionRow row);

    /** Shows only this connection's output in the console, or everyone's again if it already is. */
    void toggleOutputFilter(ConnectionRow row);

    /** Shows every connection's output in the console again. */
    void clearOutputFilter();

    void setAutoConnect(boolean isOn);

    /**
     * Stores the prefix scans look for.
     *
     * @return why it was refused, or empty when it was stored
     */
    Optional<String> setPipePrefix(String prefix);

    /** Turns the account's auto-start on or off; does nothing for a client with no account. */
    void setResumeAfterRestart(ConnectionRow row, boolean isOn);

    /** Puts the account UUID on the clipboard. */
    void copyUuid(ConnectionRow row);
}
