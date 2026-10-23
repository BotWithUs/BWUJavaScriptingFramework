package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.alerts.AlertSettings;
import com.botwithus.bot.cli.alerts.Integrations;
import com.botwithus.bot.cli.alerts.IntegrationsService;
import com.botwithus.bot.cli.alerts.LastSend;
import com.botwithus.bot.cli.alerts.NotifierSetup;
import com.botwithus.bot.cli.alerts.ServiceStatusBoard;
import com.botwithus.bot.cli.settings.AlertSettingKeys;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.SendResult;
import com.botwithus.bot.core.secrets.InMemoryCredentialStore;
import com.botwithus.bot.core.secrets.Secret;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * DEV ONLY. The real Integrations back end over the fixture's in-memory
 * settings, an in-memory credential store holding obviously fake secrets, and a
 * delivery lane that never runs — nothing is ever sent, and a Send test stays
 * "Sending test…" for the capture. Scenarios put each service in the state they
 * show by recording what a send would have left behind.
 */
public final class FixtureIntegrations {

    private static final Instant NOW = Instant.parse("2026-09-26T14:05:00Z");
    private static final Instant EARLIER = Instant.parse("2026-09-26T14:02:00Z");
    private static final Duration DELIVERED_IN = Duration.ofMillis(180);
    private static final String NOT_SENT = "The preview never sends";

    private final HostSettings settings;
    private final InMemoryCredentialStore credentials = new InMemoryCredentialStore();
    private final ServiceStatusBoard board = new ServiceStatusBoard();
    private IntegrationsService live;

    FixtureIntegrations(HostSettings settings) {
        this.settings = settings;
        this.live = build(true);
    }

    Integrations live() {
        return live;
    }

    /** As if Windows Credential Manager could not be opened: secrets last until the host closes. */
    public void keepSecretsForThisSessionOnly() {
        live = build(false);
    }

    public void switchOn(AlertService service) {
        settings.set(AlertSettingKeys.enabled(service), true);
    }

    /** Saves a fake secret, as if the user had pasted one earlier. */
    public void saveFakeSecret(AlertService service) {
        String fake = switch (service) {
            case NTFY -> "tk_preview_not_a_real_token";
            case SLACK -> "https://hooks.slack.com/services/T0000/B0000/preview-not-real";
            case DISCORD -> "https://discord.com/api/webhooks/0000000000/preview-not-a-real-token";
        };
        credentials.write(service.credentialTarget(), new Secret(fake));
    }

    /** As if a Send test had just been delivered. */
    public void testDelivered(AlertService service) {
        board.record(service, LastSend.test(new SendResult.Delivered(NOW, DELIVERED_IN, 1)));
    }

    /** As if the last send had been refused by the service. */
    public void lastSendFailed(AlertService service) {
        board.record(service, LastSend.test(new SendResult.Failed(EARLIER, "401 Unauthorized", "Unknown Webhook", 1)));
    }

    private IntegrationsService build(boolean isPersistent) {
        return new IntegrationsService(new AlertSettings(settings), credentials, isPersistent,
                service -> new NotifierSetup.NotReady(NOT_SENT), board, (service, send) -> { },
                Clock.fixed(NOW, ZoneOffset.UTC), ZoneOffset.UTC);
    }
}
