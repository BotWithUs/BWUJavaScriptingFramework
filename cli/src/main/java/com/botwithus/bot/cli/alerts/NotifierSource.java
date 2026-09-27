package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.AlertService;

/**
 * Builds the notifier for a service from its current settings and secret. Asked
 * again for every send, so a change on the Settings page applies to the next one.
 * May block on the credential store; never call it on the render thread.
 */
@FunctionalInterface
public interface NotifierSource {

    /** The notifier for {@code service} as it is set up now, or what is missing. */
    NotifierSetup forService(AlertService service);
}
