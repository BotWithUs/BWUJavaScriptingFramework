package com.botwithus.bot.core.alerts;

import java.util.Objects;
import java.util.Optional;

/**
 * How one service is set up on the launcher, as its card shows it. The secret
 * itself never leaves the launcher: only whether one is saved, and a masked hint.
 *
 * @param service          the service
 * @param hasSecret        whether a webhook URL (or, for ntfy, an access token) is saved
 * @param secretHint       what may be shown of it, e.g. {@code discord.com/…}
 * @param isMentioningHere Discord only: whether problem alerts start with {@code @here}
 * @param server           ntfy only: the server; empty for the other services
 * @param topic            ntfy only: the topic, empty until set; empty for the other services
 */
public record IntegrationState(AlertService service, boolean hasSecret, Optional<String> secretHint,
                               boolean isMentioningHere, String server, String topic) {

    public IntegrationState {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(secretHint, "secretHint");
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(topic, "topic");
    }

    /** A service the host has not heard about from the launcher yet: nothing saved. */
    public static IntegrationState unknown(AlertService service) {
        return new IntegrationState(service, false, Optional.empty(), false, "", "");
    }
}
