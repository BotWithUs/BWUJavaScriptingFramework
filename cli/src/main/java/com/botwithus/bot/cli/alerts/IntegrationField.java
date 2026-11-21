package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.IntegrationChange;
import com.botwithus.bot.core.alerts.IntegrationState;

/**
 * A plain (not secret) part of a service's set-up that the launcher keeps and an
 * Integrations card edits: the ntfy server and topic, and Discord's {@code @here}.
 */
public enum IntegrationField {

    NTFY_SERVER(AlertService.NTFY, "alerts.ntfy.server"),
    NTFY_TOPIC(AlertService.NTFY, "alerts.ntfy.topic"),
    DISCORD_MENTION_HERE(AlertService.DISCORD, "alerts.discord.mentionHere");

    private final AlertService service;
    private final String id;

    IntegrationField(AlertService service, String id) {
        this.service = service;
        this.id = id;
    }

    /** The service the field belongs to. */
    public AlertService service() {
        return service;
    }

    /**
     * A stable name for the field's control and for "Find a setting", e.g.
     * {@code alerts.ntfy.topic}: the name the host's settings file used for it
     * before the launcher kept it.
     */
    public String id() {
        return id;
    }

    /** The field's value in {@code state}, as its box shows it; {@code true}/{@code false} for the toggle. */
    public String valueIn(IntegrationState state) {
        return switch (this) {
            case NTFY_SERVER -> state.server();
            case NTFY_TOPIC -> state.topic();
            case DISCORD_MENTION_HERE -> String.valueOf(state.isMentioningHere());
        };
    }

    /** The change that sets the field to {@code text}, as typed (stripped) or toggled. */
    public IntegrationChange change(String text) {
        String value = text == null ? "" : text.strip();
        return switch (this) {
            case NTFY_SERVER -> IntegrationChange.ntfyServer(value);
            case NTFY_TOPIC -> IntegrationChange.ntfyTopic(value);
            case DISCORD_MENTION_HERE -> IntegrationChange.mentionHere(Boolean.parseBoolean(value));
        };
    }
}
