package com.botwithus.bot.core.alerts;

/**
 * A place the host can send alerts to. Each service keeps one secret in the
 * {@link com.botwithus.bot.core.secrets.CredentialStore credential store}, under
 * {@link #credentialTarget()}.
 */
public enum AlertService {

    /** ntfy push notifications, through ntfy.sh or a self-hosted server. */
    NTFY("ntfy", "ntfy", "Access token", true),
    /** A Slack channel, through an incoming webhook. */
    SLACK("slack", "Slack", "Webhook URL", false),
    /** A Discord channel, through a server webhook. */
    DISCORD("discord", "Discord", "Webhook URL", false);

    /** Every credential target starts with this. */
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

    /** Lower-case id used in setting names, e.g. {@code discord}. */
    public String id() {
        return id;
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

    /** The credential-store target holding this service's secret. */
    public String credentialTarget() {
        return CREDENTIAL_TARGET_PREFIX + id;
    }
}
