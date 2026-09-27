package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.AlertService;

import java.util.Objects;
import java.util.Optional;

/**
 * One Integrations service card, ready to draw. Immutable: take a fresh one each frame.
 *
 * @param service   the service
 * @param isEnabled whether its switch is on
 * @param hasSecret whether its webhook URL or token is saved
 * @param status    the state next to its name
 * @param lastLine  the result line under Send test, e.g. {@code 401 Unauthorized · Unknown Webhook (14:02)}
 */
public record ServiceView(AlertService service, boolean isEnabled, boolean hasSecret,
                          ServiceStatus status, Optional<String> lastLine) {

    public ServiceView {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(lastLine, "lastLine");
    }
}
