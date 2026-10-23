package com.botwithus.bot.core.launcher;

import java.util.Objects;

/**
 * The service asked this host to close, so that a data update can apply
 * ({@code host.close_requested}, ADR 6.3). Nothing happens unless the user
 * agrees; the host never quits on its own.
 *
 * @param requestId     the id to answer with, through {@link LauncherService#ackClose}
 * @param reason        why; {@code "data_update"} in protocol 1
 * @param hostsBlocking how many hosts the update is waiting for
 */
public record CloseRequest(long requestId, String reason, long hostsBlocking) {
    public CloseRequest {
        Objects.requireNonNull(reason, "reason");
    }
}
