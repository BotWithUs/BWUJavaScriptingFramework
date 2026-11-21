package com.botwithus.bot.core.alerts;

import java.util.Optional;

/**
 * A place alerts can be sent to. The launcher service delivers them and keeps each
 * service's connection settings; the host only chooses which services get what.
 */
public enum AlertService {

    /** ntfy push notifications, through ntfy.sh or a self-hosted server. */
    NTFY("ntfy", "ntfy", "Access token", true),
    /** A Slack channel, through an incoming webhook. */
    SLACK("slack", "Slack", "Webhook URL", false),
    /** A Discord channel, through a server webhook. */
    DISCORD("discord", "Discord", "Webhook URL", false);

    /** Every target the host used to keep a secret under starts with this. */
    public static final String CREDENTIAL_TARGET_PREFIX = "BotWithUs/integrations/";

    private final String id;
    private final String label;
    private final String secretLabel;
    private final boolean isSecretOptional;

    AlertService(String id, String label, String secretLabel, boolean isSecretOptional) {
        this.id = id;
        this.label = label;
        this.secretLabel = secretLabel;
        this.isSecretOptional = isSecretOptional;
    }

    /**
     * Lower-case id used in setting names and, spelled the same, as the service's
     * name on the launcher's {@code alerts.*} methods, e.g. {@code discord}.
     */
    public String id() {
        return id;
    }

    /** The service whose {@link #id()} is {@code id}, if there is one. */
    public static Optional<AlertService> fromId(String id) {
        for (AlertService service : values()) {
            if (service.id.equals(id)) {
                return Optional.of(service);
            }
        }
        return Optional.empty();
    }

    /** Name to show, e.g. {@code Discord}. */
    public String label() {
        return label;
    }

    /** What the service's secret is, e.g. {@code Webhook URL}. */
    public String secretLabel() {
        return secretLabel;
    }

    /** Whether the service works without its secret (ntfy's token is optional). */
    public boolean isSecretOptional() {
        return isSecretOptional;
    }

    /**
     * The credential-store target the host kept this service's secret under before
     * the launcher took delivery over. Only the one-time move of that secret to the
     * launcher reads it, and it deletes the entry once the launcher holds the secret.
     */
    public String credentialTarget() {
        return CREDENTIAL_TARGET_PREFIX + id;
    }
}
