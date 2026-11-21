package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.alerts.AlertSettings;
import com.botwithus.bot.cli.alerts.IntegrationMigration;
import com.botwithus.bot.cli.alerts.Integrations;
import com.botwithus.bot.cli.alerts.IntegrationsService;
import com.botwithus.bot.cli.alerts.LastSend;
import com.botwithus.bot.cli.alerts.ServiceStatusBoard;
import com.botwithus.bot.cli.settings.AlertSettingKeys;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.IntegrationState;
import com.botwithus.bot.core.alerts.SendResult;
import com.botwithus.bot.core.secrets.InMemoryCredentialStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * DEV ONLY. The real Integrations back end over the fixture's in-memory settings
 * and {@link FixtureAlertGateway}, a stand-in launcher that sends nothing — a Send
 * test stays "Sending test…" for the capture. Scenarios put each service in the
 * state they show by recording what a send would have left behind.
 */
public final class FixtureIntegrations {

    private static final Instant NOW = Instant.parse("2026-09-26T14:05:00Z");
    private static final Instant EARLIER = Instant.parse("2026-09-26T14:02:00Z");
    private static final Duration DELIVERED_IN = Duration.ofMillis(180);
    private static final String TOKEN_HINT = "************";

    private final HostSettings settings;
    private final FixtureAlertGateway gateway = new FixtureAlertGateway();
    private final ServiceStatusBoard board = new ServiceStatusBoard();
    private final IntegrationsService live;

    FixtureIntegrations(HostSettings settings) {
        this.settings = settings;
        this.live = new IntegrationsService(new AlertSettings(settings), gateway,
                new IntegrationMigration(gateway, new InMemoryCredentialStore(), settings), board,
                (at, task) -> () -> { }, Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC), ZoneOffset.UTC);
        live.onAvailable();
    }

    Integrations live() {
        return live;
    }

    /** As if the connected launcher predated alerts: the section says to update it. */
    public void launcherPredatesAlerts() {
        gateway.predateAlerts();
    }

    public void switchOn(AlertService service) {
        settings.set(AlertSettingKeys.enabled(service), true);
    }

    /** As if the user had saved a secret earlier: the launcher reports it with its masked hint. */
    public void saveFakeSecret(AlertService service) {
        IntegrationState now = gateway.setup(service);
        String hint = switch (service) {
            case NTFY -> TOKEN_HINT;
            case SLACK -> "hooks.slack.com/…";
            case DISCORD -> "discord.com/…";
        };
        gateway.put(new IntegrationState(service, true, Optional.of(hint), now.isMentioningHere(), now.server(),
                now.topic()));
        live.onAvailable();
    }

    /** As if the user had set an ntfy topic earlier. */
    public void ntfyTopic(String topic) {
        IntegrationState now = gateway.setup(AlertService.NTFY);
        gateway.put(new IntegrationState(AlertService.NTFY, now.hasSecret(), now.secretHint(), false, now.server(),
                topic));
        live.onAvailable();
    }

    /** As if Discord's {@code @here} had been switched on earlier. */
    public void mentionHere() {
        IntegrationState now = gateway.setup(AlertService.DISCORD);
        gateway.put(new IntegrationState(AlertService.DISCORD, now.hasSecret(), now.secretHint(), true, "", ""));
        live.onAvailable();
    }

    /** As if a Send test had just been delivered. */
    public void testDelivered(AlertService service) {
        board.record(service, LastSend.test(new SendResult.Delivered(NOW, DELIVERED_IN, 1)));
    }

    /** As if the last send had been refused by the service. */
    public void lastSendFailed(AlertService service) {
        board.record(service, LastSend.test(new SendResult.Failed(EARLIER, "401 Unauthorized", "Unknown Webhook", 1)));
    }
}
