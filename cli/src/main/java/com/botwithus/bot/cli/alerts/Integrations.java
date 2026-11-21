package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.SendResult;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * What the Integrations section of the Settings page binds to, beside the plain
 * {@code alerts.*} settings (switches, the event grid, bursts, quiet hours, summary
 * time), which it reads and writes through
 * {@link com.botwithus.bot.cli.settings.HostSettings} like any other setting.
 *
 * <p>Each service's connection set-up (the webhook URL or token, the ntfy server
 * and topic, Discord's {@code @here}) is kept by the launcher service, which also
 * sends every alert. This host never holds a secret: it hands a new one to the
 * launcher and from then on knows only whether one is saved, and a masked hint.</p>
 */
public interface Integrations {

    /** Whether alerts can be set up and sent now, and if not, why. */
    enum Availability {
        /** A launcher that sends alerts is connected. */
        READY,
        /** The connected launcher predates alerts; nothing is sent until it is updated. */
        NEEDS_NEWER_LAUNCHER,
        /** No launcher is connected. */
        NO_LAUNCHER
    }

    /** Whether alerts can be set up and sent now. Cheap; safe to call every frame. */
    Availability availability();

    /** One card per service, in display order. Cheap; safe to call every frame. */
    List<ServiceView> services();

    /** The card for {@code service}. Cheap; safe to call every frame. */
    ServiceView service(AlertService service);

    /**
     * Hands {@code value} to the launcher as the service's secret, or removes the
     * saved one when it is blank. Returns at once; the future completes off the
     * calling thread when the launcher has answered.
     */
    CompletableFuture<SecretChange> saveSecret(AlertService service, String value);

    /**
     * Sets one plain field of a service's set-up on the launcher. Returns at once;
     * a refusal shows under the field, through {@link ServiceView#fieldError}.
     */
    void change(IntegrationField field, String value);

    /**
     * Asks the launcher to send a test message to {@code service} as it is set up
     * there, whether or not it is switched on here. The card shows
     * {@link ServiceStatus#SENDING} until the result is in; the future completes with
     * it, off the calling thread.
     */
    CompletableFuture<SendResult> sendTest(AlertService service);

    /** Why the last change to {@code field} was refused, until it is changed again. */
    Optional<String> fieldError(IntegrationField field);
}
