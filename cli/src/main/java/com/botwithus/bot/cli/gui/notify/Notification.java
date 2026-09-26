package com.botwithus.bot.cli.gui.notify;

import java.time.Instant;
import java.util.UUID;

/**
 * One transient toast shown by {@link NotificationOverlay}. Built from an
 * incoming event; carries a TTL so the overlay can auto-dismiss.
 *
 * @param id        stable identifier (used as ImGui ID seed)
 * @param kind      which event produced it; decides icon, colour and action
 * @param severity  visual weight (INFO / WARN / ERROR)
 * @param title     short title rendered in medium weight
 * @param message   one-line body
 * @param subject   what the action acts on: a connection name, or a JAR path
 * @param createdAt when it was posted, for the slide-in and the life bar
 * @param expiresAt absolute instant after which {@link #isExpired} returns true
 */
public record Notification(UUID id, Kind kind, Severity severity, String title, String message,
                           String subject, Instant createdAt, Instant expiresAt) {

    public enum Severity { INFO, WARN, ERROR }

    /** The six events that raise a toast. */
    public enum Kind {
        CONNECTION_LOST(null),
        RECONNECTING(null),
        RECONNECTED(null),
        GAVE_UP("Try again"),
        SCRIPT_CRASHED("View log"),
        LOAD_FAILED("Details");

        private final String actionLabel;

        Kind(String actionLabel) {
            this.actionLabel = actionLabel;
        }

        /** The toast's one action button, or {@code null} when it has none. */
        public String actionLabel() {
            return actionLabel;
        }

        /** Whether the body is an identifier (exception, path) and reads better in mono. */
        public boolean isMonoBody() {
            return this == SCRIPT_CRASHED || this == LOAD_FAILED;
        }
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
