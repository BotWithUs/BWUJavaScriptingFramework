package com.botwithus.bot.cli.gui.usermode.board;

/**
 * Host-wide facts the status bar and the empty / offline screens need.
 *
 * @param hostOffline      every client has given up reconnecting
 * @param gaveUpAttempts   attempts made before giving up (meaningful when offline)
 * @param activeClient     the active connection's pipe name, or {@code null}
 * @param autoConnect      whether background pipe scanning is on
 * @param pipePattern      the pipe names the host scans for, for the empty state
 * @param mountedClient    the mounted connection's name, or {@code null}
 * @param watchingScripts  whether the scripts-directory watcher is running
 */
public record BoardStatus(
        boolean hostOffline,
        int gaveUpAttempts,
        String activeClient,
        boolean autoConnect,
        String pipePattern,
        String mountedClient,
        boolean watchingScripts) {
}
