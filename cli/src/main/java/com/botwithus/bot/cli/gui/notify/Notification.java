package com.botwithus.bot.cli.gui.notify;

import java.time.Instant;
import java.util.UUID;

/**
 * One transient banner shown by {@link NotificationOverlay}. Built from an
 * incoming event; carries a TTL so the overlay can auto-dismiss.
 *
 * @param id        stable identifier (used as ImGui ID seed)
 * @param severity  visual weight (INFO / WARN / ERROR)
 * @param title     short title rendered in bold
 * @param message   one-line body
 * @param expiresAt absolute instant after which {@link #isExpired} returns true
 */
public record Notification(UUID id, Severity severity, String title, String message, Instant expiresAt) {

    public enum Severity { INFO, WARN, ERROR }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
