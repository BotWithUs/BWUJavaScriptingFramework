package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.cli.settings.AlertSettingKeys;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.core.alerts.AlertMessage;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.SendResult;
import com.botwithus.bot.core.secrets.CredentialStore;
import com.botwithus.bot.core.secrets.InMemoryCredentialStore;
import com.botwithus.bot.core.secrets.Secret;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationsServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String DISCORD_HOOK = "https://discord.com/api/webhooks/1182/Xk9secret";

    @TempDir
    Path dir;

    private final ManualClock clock = new ManualClock(Instant.parse("2026-09-26T12:02:00Z"));
    private final RecordingNotifiers notifiers = new RecordingNotifiers(clock);
    private final ServiceStatusBoard status = new ServiceStatusBoard();
    private final CredentialStore credentials = new InMemoryCredentialStore();
    private final Deque<Runnable> lane = new ArrayDeque<>();
    private HostSettings settings;
    private IntegrationsService integrations;

    @BeforeEach
    void open() {
        settings = HostSettings.open(dir);
        integrations = new IntegrationsService(new AlertSettings(settings), credentials, true, notifiers,
                status, (service, send) -> lane.add(send), clock, BERLIN);
    }

    @AfterEach
    void close() {
        settings.close();
    }

    private void runLane() {
        while (!lane.isEmpty()) {
            lane.poll().run();
        }
    }

    @Test
    void services_areAllOffAndUntestedAtFirst() {
        List<ServiceView> views = integrations.services();

        assertEquals(List.of(AlertService.NTFY, AlertService.SLACK, AlertService.DISCORD),
                views.stream().map(ServiceView::service).toList());
        for (ServiceView view : views) {
            assertEquals(new ServiceView(view.service(), false, false, ServiceStatus.OFF, Optional.empty()), view);
        }
    }

    @Test
    void service_switchedOn_isNotTested() {
        settings.set(AlertSettingKeys.enabled(AlertService.DISCORD), true);

        assertEquals(ServiceStatus.NOT_TESTED, integrations.service(AlertService.DISCORD).status());
    }

    @Test
    void sendTest_showsSendingThenTheResult() {
        settings.set(AlertSettingKeys.enabled(AlertService.DISCORD), true);

        CompletableFuture<SendResult> result = integrations.sendTest(AlertService.DISCORD);

        assertEquals(ServiceStatus.SENDING, integrations.service(AlertService.DISCORD).status());
        assertFalse(result.isDone(), "the send happens on the service's lane");
        runLane();
        assertTrue(result.join().isDelivered());
        ServiceView view = integrations.service(AlertService.DISCORD);
        assertEquals(ServiceStatus.WORKING, view.status());
        assertEquals(Optional.of("Test delivered 14:02 · 120 ms"), view.lastLine());
        assertEquals(List.of(IntegrationsService.TEST_MESSAGE), notifiers.sentTo(AlertService.DISCORD));
    }

    @Test
    void sendTest_failure_showsStatusDotReason() {
        settings.set(AlertSettingKeys.enabled(AlertService.DISCORD), true);
        notifiers.answer(AlertService.DISCORD,
                () -> new SendResult.Failed(clock.instant(), "401 Unauthorized", "Unknown Webhook", 1));

        integrations.sendTest(AlertService.DISCORD);
        runLane();

        ServiceView view = integrations.service(AlertService.DISCORD);
        assertEquals(ServiceStatus.FAILED, view.status());
        assertEquals(Optional.of("401 Unauthorized · Unknown Webhook (14:02)"), view.lastLine());
    }

    @Test
    void sendTest_notSetUp_saysWhatIsMissing() {
        notifiers.notReady(AlertService.SLACK, "Add the webhook URL first");

        SendResult result = sendTestAndRun(AlertService.SLACK);

        assertEquals("Not set up · Add the webhook URL first", result.summary());
        assertEquals(List.<AlertMessage>of(), notifiers.sentTo(AlertService.SLACK));
    }

    @Test
    void sendTest_worksWhileTheServiceIsOff_andTheCardStaysOff() {
        assertTrue(sendTestAndRun(AlertService.NTFY).isDelivered());

        assertEquals(ServiceStatus.OFF, integrations.service(AlertService.NTFY).status());
        assertEquals(Optional.of("Test delivered 14:02 · 120 ms"),
                integrations.service(AlertService.NTFY).lastLine());
    }

    @Test
    void saveSecret_storesItUnderTheServiceTarget() {
        assertEquals(new SecretChange.Saved(), integrations.saveSecret(AlertService.DISCORD, " " + DISCORD_HOOK + " "));

        assertEquals(Optional.of(new Secret(DISCORD_HOOK)), credentials.read("BotWithUs/integrations/discord"));
        assertEquals(Optional.of(new Secret(DISCORD_HOOK)), integrations.readSecret(AlertService.DISCORD));
        assertTrue(integrations.service(AlertService.DISCORD).hasSecret());
    }

    @Test
    void saveSecret_blank_removesTheSavedOne() {
        integrations.saveSecret(AlertService.DISCORD, DISCORD_HOOK);

        assertEquals(new SecretChange.Cleared(), integrations.saveSecret(AlertService.DISCORD, "  "));

        assertEquals(Optional.empty(), credentials.read(AlertService.DISCORD.credentialTarget()));
        assertFalse(integrations.service(AlertService.DISCORD).hasSecret());
    }

    @Test
    void saveSecret_webhookThatIsNotHttps_isRefusedWithoutEchoingIt() {
        SecretChange change = integrations.saveSecret(AlertService.SLACK, "http://hooks.slack.com/services/T/B/secret");

        assertEquals(new SecretChange.Refused("The webhook URL must start with https://"), change);
        assertEquals(Optional.empty(), credentials.read(AlertService.SLACK.credentialTarget()));
    }

    @Test
    void saveSecret_ntfyToken_isAnyText() {
        assertEquals(new SecretChange.Saved(), integrations.saveSecret(AlertService.NTFY, "tk_abc"));
    }

    @Test
    void saveSecret_tooLong_isRefused() {
        SecretChange change = integrations.saveSecret(AlertService.NTFY,
                "x".repeat(CredentialStore.MAX_SECRET_CHARS + 1));

        boolean isRefused = switch (change) {
            case SecretChange.Refused refused -> refused.reason().contains("at most");
            case SecretChange.Saved saved -> false;
            case SecretChange.Cleared cleared -> false;
        };
        assertTrue(isRefused, change.toString());
        assertEquals(Optional.empty(), credentials.read(AlertService.NTFY.credentialTarget()));
    }

    @Test
    void hasSecret_reflectsWhatWasAlreadyStored() {
        credentials.write(AlertService.SLACK.credentialTarget(), new Secret("https://hooks.slack.com/x"));
        IntegrationsService reopened = new IntegrationsService(new AlertSettings(settings), credentials, true,
                notifiers, status, DeliveryLanes.direct(), clock, BERLIN);

        assertTrue(reopened.service(AlertService.SLACK).hasSecret());
    }

    private SendResult sendTestAndRun(AlertService service) {
        CompletableFuture<SendResult> result = integrations.sendTest(service);
        runLane();
        return result.join();
    }
}
