package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.SendResult;
import com.botwithus.bot.core.secrets.Secret;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * What the Integrations section of the Settings page binds to, beside the plain
 * {@code alerts.*} settings (switches, ntfy server and topic, the event grid, bursts,
 * quiet hours, summary time), which it reads and writes through
 * {@link com.botwithus.bot.cli.settings.HostSettings} like any other setting.
 *
 * <p>Secrets — the Slack and Discord webhook URLs and the ntfy token — go through
 * here and never through the settings file.</p>
 */
public interface Integrations {

    /** One card per service, in display order. Cheap; safe to call every frame. */
    List<ServiceView> services();

    /** The card for {@code service}. Cheap; safe to call every frame. */
    ServiceView service(AlertService service);

    /**
     * The saved secret, for the reveal button. Reads the credential store, so call
     * it when the user asks to see the value, not every frame.
     */
    Optional<Secret> readSecret(AlertService service);

    /**
     * Saves {@code value} as the service's secret, or removes the saved one when it
     * is blank. Writes the credential store; call it on commit, not per keystroke.
     */
    SecretChange saveSecret(AlertService service, String value);

    /**
     * Sends a test message to {@code service} as it is set up now, whether or not it
     * is switched on. The card shows {@link ServiceStatus#SENDING} until the result is
     * in; the future completes with it, off the calling thread.
     */
    CompletableFuture<SendResult> sendTest(AlertService service);

    /**
     * Whether saved secrets survive a restart. {@code false} when the operating
     * system's credential store could not be opened and secrets are kept in memory.
     */
    boolean isPersistent();
}
