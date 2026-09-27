package com.botwithus.bot.cli.gui.notify;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.settings.NotificationKind;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One toast as {@link NotificationOverlay} shows it: a {@link Toast} that has
 * been put on screen, with the moment it appeared and, unless it stays until
 * closed, the moment it goes.
 *
 * @param id        stable identifier (used as ImGui ID seed); kept when a
 *                  toast is updated in place
 * @param kind      which event produced it; decides icon, colour and action
 * @param title     short title rendered in medium weight
 * @param message   one-line body
 * @param client    the client the toast is about, if any; its action acts on it
 * @param createdAt when it was put on screen, for the slide-in and the life bar
 * @param expiresAt when it goes; empty for a toast that stays until closed
 */
public record Notification(UUID id, Kind kind, String title, String message, Optional<ClientKey> client,
                           Instant createdAt, Optional<Instant> expiresAt) {

    public Notification {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public enum Severity { INFO, WARN, ERROR }

    /** What a toast's one action button does. */
    public enum Action {
        /** No button. */
        NONE(null),
        /** Retries the client's connection at once. */
        TRY_AGAIN("Try again"),
        /** Opens the Logs tab scoped to the client. */
        VIEW_LOG("View log"),
        /** Opens the Logs tab for every client: the failure belongs to none. */
        DETAILS("Details");

        private final String label;

        Action(String label) {
            this.label = label;
        }

        /** The button's text, or {@code null} for {@link #NONE}. */
        public String label() {
            return label;
        }
    }

    /**
     * The events that raise a toast. Each belongs to one of the Settings page's
     * {@link NotificationKind} switches. The ones that describe a client's
     * connection are {@linkplain #isConnectionState() one per client}: a newer
     * one replaces the one on screen instead of stacking under it.
     */
    public enum Kind {
        CONNECTION_LOST(Severity.WARN, NotificationKind.CLIENT_LOST, Action.NONE),
        RECONNECTING(Severity.WARN, NotificationKind.CLIENT_LOST, Action.NONE),
        GAVE_UP(Severity.ERROR, NotificationKind.CLIENT_LOST, Action.TRY_AGAIN),
        CLIENT_CLOSED(Severity.INFO, NotificationKind.CLIENT_LOST, Action.NONE),
        RECONNECTED(Severity.INFO, NotificationKind.CLIENT_BACK, Action.NONE),
        CLIENT_RESUMED(Severity.INFO, NotificationKind.CLIENT_BACK, Action.NONE),
        SCRIPT_STALLED(Severity.WARN, NotificationKind.SCRIPT_CRASH, Action.VIEW_LOG),
        SCRIPT_CRASHED(Severity.ERROR, NotificationKind.SCRIPT_CRASH, Action.VIEW_LOG),
        LOAD_FAILED(Severity.ERROR, NotificationKind.LOAD_FAILED, Action.DETAILS);

        private final Severity severity;
        private final NotificationKind setting;
        private final Action action;

        Kind(Severity severity, NotificationKind setting, Action action) {
            this.severity = severity;
            this.setting = setting;
            this.action = action;
        }

        public Severity severity() {
            return severity;
        }

        /** The Settings switch that turns this toast on or off. */
        public NotificationKind setting() {
            return setting;
        }

        public Action action() {
            return action;
        }

        /** Whether the body is an identifier (exception, path) and reads better in mono. */
        public boolean isMonoBody() {
            return this == SCRIPT_CRASHED || this == LOAD_FAILED;
        }

        /** Whether this says where a client's connection stands, so one per client is enough. */
        public boolean isConnectionState() {
            return setting == NotificationKind.CLIENT_LOST || setting == NotificationKind.CLIENT_BACK;
        }

        /** Whether this is a client being down and retried, which later attempts update in place. */
        public boolean isOutage() {
            return this == CONNECTION_LOST || this == RECONNECTING;
        }
    }

    public Severity severity() {
        return kind.severity();
    }

    /** Whether the toast has gone by {@code now}. A toast that stays until closed never has. */
    public boolean isExpired(Instant now) {
        return expiresAt.map(now::isAfter).orElse(false);
    }
}
