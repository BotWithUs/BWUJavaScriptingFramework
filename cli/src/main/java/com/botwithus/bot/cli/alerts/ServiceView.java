package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.IntegrationState;

import java.util.Objects;
import java.util.Optional;

/**
 * One Integrations service card, ready to draw. Immutable: take a fresh one each frame.
 *
 * @param service   the service
 * @param isEnabled whether its switch is on
 * @param setup     how it is set up on the launcher, as last read; never the secret itself
 * @param status    the state next to its name
 * @param lastLine  the result line under Send test, e.g. {@code 401 Unauthorized · Unknown Webhook (14:02)}
 */
public record ServiceView(AlertService service, boolean isEnabled, IntegrationState setup,
                          ServiceStatus status, Optional<String> lastLine) {

    public ServiceView {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(setup, "setup");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(lastLine, "lastLine");
    }

    /** Whether its webhook URL or token is saved on the launcher. */
    public boolean hasSecret() {
        return setup.hasSecret();
    }

    /** What may be shown of the saved secret, e.g. {@code discord.com/…}. */
    public Optional<String> secretHint() {
        return setup.secretHint();
    }
}
