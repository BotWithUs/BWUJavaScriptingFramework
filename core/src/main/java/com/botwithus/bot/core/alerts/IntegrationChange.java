package com.botwithus.bot.core.alerts;

import com.botwithus.bot.core.secrets.Secret;

import java.util.Objects;
import java.util.Optional;

/**
 * A change to one service's set-up on the launcher. Only what is present changes;
 * the rest stays as it is.
 *
 * @param service          the service
 * @param secret           the new webhook URL or token, or empty to leave it alone
 * @param isClearingSecret whether to remove the saved secret; never together with {@code secret}
 * @param isMentioningHere Discord only: the new {@code @here} setting
 * @param server           ntfy only: the new server
 * @param topic            ntfy only: the new topic; empty text clears it
 */
public record IntegrationChange(AlertService service, Optional<Secret> secret, boolean isClearingSecret,
                                Optional<Boolean> isMentioningHere, Optional<String> server,
                                Optional<String> topic) {

    public IntegrationChange {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(isMentioningHere, "isMentioningHere");
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(topic, "topic");
        if (isClearingSecret && secret.isPresent()) {
            throw new IllegalArgumentException("a change either sets the secret or clears it");
        }
    }

    /** Sets the secret and nothing else. */
    public static IntegrationChange secret(AlertService service, Secret secret) {
        return new IntegrationChange(service, Optional.of(secret), false, Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    /** Removes the saved secret and changes nothing else. */
    public static IntegrationChange clearSecret(AlertService service) {
        return new IntegrationChange(service, Optional.empty(), true, Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    /** Discord's {@code @here} setting and nothing else. */
    public static IntegrationChange mentionHere(boolean isOn) {
        return new IntegrationChange(AlertService.DISCORD, Optional.empty(), false, Optional.of(isOn),
                Optional.empty(), Optional.empty());
    }

    /** The ntfy server and nothing else. */
    public static IntegrationChange ntfyServer(String server) {
        return new IntegrationChange(AlertService.NTFY, Optional.empty(), false, Optional.empty(),
                Optional.of(server), Optional.empty());
    }

    /** The ntfy topic and nothing else. */
    public static IntegrationChange ntfyTopic(String topic) {
        return new IntegrationChange(AlertService.NTFY, Optional.empty(), false, Optional.empty(),
                Optional.empty(), Optional.of(topic));
    }
}
