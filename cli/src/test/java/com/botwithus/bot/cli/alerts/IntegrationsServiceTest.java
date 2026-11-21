package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.cli.settings.AlertSettingKeys;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.core.alerts.AlertGateway;
import com.botwithus.bot.core.alerts.AlertResult;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.IntegrationReply;
import com.botwithus.bot.core.alerts.IntegrationState;
import com.botwithus.bot.core.alerts.SendResult;
import com.botwithus.bot.core.alerts.ServiceState;
import com.botwithus.bot.core.alerts.TestReply;
import com.botwithus.bot.core.secrets.CredentialStore;
import com.botwithus.bot.core.secrets.InMemoryCredentialStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationsServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    /** Obviously fake: nothing is ever sent to it. */
    private static final String DISCORD_HOOK = "https://discord.com/api/webhooks/1182/not-a-real-token";

    @TempDir
    Path dir;

    private final ManualClock clock = new ManualClock(Instant.parse("2026-09-26T12:02:00Z"));
    private final ManualScheduler scheduler = new ManualScheduler(clock);
    private final FakeLauncher launcher = new FakeLauncher(clock);
    private final ServiceStatusBoard status = new ServiceStatusBoard();
    private final CredentialStore hostCredentials = new InMemoryCredentialStore();
    private final Deque<Runnable> background = new ArrayDeque<>();
    private HostSettings settings;
    private IntegrationsService integrations;

    @BeforeEach
    void open() {
        settings = HostSettings.open(dir);
        integrations = new IntegrationsService(new AlertSettings(settings), launcher,
                new IntegrationMigration(launcher, hostCredentials, settings), status, scheduler, background::add,
                clock, BERLIN);
        launcher.addListener(integrations);
    }

    @AfterEach
    void close() {
        settings.close();
    }

    private void runBackground() {
        while (!background.isEmpty()) {
            background.poll().run();
        }
    }

    private void discordIsSetUp() {
        launcher.setup(new IntegrationState(AlertService.DISCORD, true, Optional.of("discord.com/…"), false, "", ""));
    }

    // ── Cards ──────────────────────────────────────────────────────────────

    @Test
    void services_areAllOffUntestedAndNotSetUpAtFirst() {
        List<ServiceView> views = integrations.services();

        assertEquals(List.of(AlertService.NTFY, AlertService.SLACK, AlertService.DISCORD),
                views.stream().map(ServiceView::service).toList());
        for (ServiceView view : views) {
            assertEquals(new ServiceView(view.service(), false, IntegrationState.unknown(view.service()),
                    ServiceStatus.OFF, Optional.empty()), view);
        }
    }

    @Test
    void aLauncherConnecting_isReadForEachServicesSetUp() {
        discordIsSetUp();

        integrations.onAvailable();
        runBackground();

        ServiceView discord = integrations.service(AlertService.DISCORD);
        assertAll(() -> assertTrue(discord.hasSecret()),
                () -> assertEquals(Optional.of("discord.com/…"), discord.secretHint()));
    }

    @Test
    void availability_saysWhetherTheLauncherSendsAlerts() {
        assertEquals(Integrations.Availability.READY, integrations.availability());
        launcher.predatingAlerts();
        assertEquals(Integrations.Availability.NEEDS_NEWER_LAUNCHER, integrations.availability());
        launcher.gone();
        assertEquals(Integrations.Availability.NO_LAUNCHER, integrations.availability());
    }

    @Test
    void service_switchedOn_isNotTested() {
        settings.set(AlertSettingKeys.enabled(AlertService.DISCORD), true);

        assertEquals(ServiceStatus.NOT_TESTED, integrations.service(AlertService.DISCORD).status());
    }

    // ── Secrets ────────────────────────────────────────────────────────────

    @Test
    void saveSecret_handsItToTheLauncher_andNeverToTheHostsOwnStore() {
        CompletableFuture<SecretChange> change = integrations.saveSecret(AlertService.DISCORD, "  " + DISCORD_HOOK);
        runBackground();

        assertAll(() -> assertEquals(new SecretChange.Saved(), change.join()),
                () -> assertEquals(Optional.of(DISCORD_HOOK), launcher.savedSecret(AlertService.DISCORD),
                        "stripped"),
                () -> assertTrue(integrations.service(AlertService.DISCORD).hasSecret()),
                () -> assertEquals(Optional.empty(), hostCredentials.read(AlertService.DISCORD.credentialTarget())));
    }

    @Test
    void saveSecret_blank_clearsTheLaunchersCopy() {
        discordIsSetUp();
        integrations.onAvailable();
        runBackground();

        CompletableFuture<SecretChange> change = integrations.saveSecret(AlertService.DISCORD, " ");
        runBackground();

        assertAll(() -> assertEquals(new SecretChange.Cleared(), change.join()),
                () -> assertTrue(launcher.changes().getLast().isClearingSecret()),
                () -> assertFalse(integrations.service(AlertService.DISCORD).hasSecret()));
    }

    @Test
    void saveSecret_refused_saysWhyInTheLaunchersWords_neverRepeatingTheSecret() {
        CompletableFuture<SecretChange> change = integrations.saveSecret(AlertService.SLACK, "http://x/not-real");
        runBackground();

        SecretChange.Refused refused = assertInstanceOf(SecretChange.Refused.class, change.join());
        assertAll(() -> assertEquals(FakeLauncher.HTTPS_REQUIRED, refused.reason()),
                () -> assertFalse(refused.reason().contains("not-real")));
    }

    @Test
    void saveSecret_tooLong_isRefusedWithoutAskingTheLauncher() {
        String tooLong = "https://" + "a".repeat(CredentialStore.MAX_SECRET_CHARS);

        SecretChange change = integrations.saveSecret(AlertService.SLACK, tooLong).join();

        assertAll(() -> assertEquals(new SecretChange.Refused("The webhook URL can be at most "
                        + CredentialStore.MAX_SECRET_CHARS + " characters"), change),
                () -> assertEquals(List.of(), launcher.changes()));
    }

    @Test
    void saveSecret_onAnOlderLauncher_isRefused_andChangesNothing() {
        launcher.predatingAlerts();

        CompletableFuture<SecretChange> change = integrations.saveSecret(AlertService.DISCORD, DISCORD_HOOK);
        runBackground();

        assertAll(() -> assertEquals(new SecretChange.Refused(AlertGateway.NEEDS_NEWER_LAUNCHER), change.join()),
                () -> assertEquals(Optional.empty(), launcher.savedSecret(AlertService.DISCORD)));
    }

    @Test
    void aFieldChange_goesToTheLauncher_andARefusalShowsUnderTheField_untilTheNextChange() {
        launcher.replyToNextChange(new IntegrationReply.Refused(Optional.of("topic"),
                "The topic can be up to 64 letters, digits, '_' or '-'"));

        integrations.change(IntegrationField.NTFY_TOPIC, "has spaces");
        runBackground();
        Optional<String> refused = integrations.fieldError(IntegrationField.NTFY_TOPIC);
        integrations.change(IntegrationField.NTFY_TOPIC, "bwu-alerts");
        Optional<String> cleared = integrations.fieldError(IntegrationField.NTFY_TOPIC);
        runBackground();

        assertAll(() -> assertTrue(refused.isPresent()),
                () -> assertEquals(Optional.empty(), cleared),
                () -> assertEquals("bwu-alerts", integrations.service(AlertService.NTFY).setup().topic()));
    }

    // ── Send test ──────────────────────────────────────────────────────────

    @Test
    void sendTest_isSendingUntilItsResultArrives_thenRecordsIt() {
        discordIsSetUp();
        settings.set(AlertSettingKeys.enabled(AlertService.DISCORD), true);

        CompletableFuture<SendResult> done = integrations.sendTest(AlertService.DISCORD);
        runBackground();
        ServiceStatus whileSending = integrations.service(AlertService.DISCORD).status();
        launcher.reportAll();

        assertAll(() -> assertEquals(ServiceStatus.SENDING, whileSending),
                () -> assertTrue(done.join().isDelivered()),
                () -> assertEquals(ServiceStatus.WORKING, integrations.service(AlertService.DISCORD).status()),
                () -> assertTrue(status.last(AlertService.DISCORD).orElseThrow().isTest()));
    }

    @Test
    void sendTest_ignoresTheSwitch() {
        discordIsSetUp();

        integrations.sendTest(AlertService.DISCORD);
        runBackground();

        assertEquals(List.of(AlertService.DISCORD), launcher.tests());
    }

    @Test
    void sendTest_isCompletedOnlyByItsOwnResult() {
        discordIsSetUp();
        CompletableFuture<SendResult> done = integrations.sendTest(AlertService.DISCORD);
        runBackground();

        launcher.report(result(Long.MAX_VALUE, true));

        assertFalse(done.isDone(), "another test's result does not finish this one");
    }

    @Test
    void sendTest_whoseResultBeatsTheAnswer_stillCompletes() {
        discordIsSetUp();
        launcher.replyToNextTest(new TestReply.Queued(7));
        launcher.report(result(7, true));

        CompletableFuture<SendResult> done = integrations.sendTest(AlertService.DISCORD);
        runBackground();

        assertTrue(done.join().isDelivered());
    }

    @Test
    void sendTest_toAServiceNotSetUp_saysWhatIsMissing_atOnce() {
        CompletableFuture<SendResult> done = integrations.sendTest(AlertService.SLACK);
        runBackground();

        assertEquals("Not set up · Add the webhook URL first", done.join().summary());
    }

    @Test
    void sendTest_withNoResult_givesUpAfterTheTimeout() {
        discordIsSetUp();
        CompletableFuture<SendResult> done = integrations.sendTest(AlertService.DISCORD);
        runBackground();

        scheduler.advance(IntegrationsService.TEST_TIMEOUT);

        assertAll(() -> assertEquals("No answer · The launcher did not report a result", done.join().summary()),
                () -> assertFalse(status.isSending(AlertService.DISCORD), "the card stops saying Sending"));
    }

    @Test
    void sendTest_whenTheLauncherGoesAway_endsAtOnce() {
        discordIsSetUp();
        CompletableFuture<SendResult> done = integrations.sendTest(AlertService.DISCORD);
        runBackground();

        integrations.onUnavailable();

        assertEquals("No answer · " + IntegrationsService.WENT_AWAY, done.join().summary());
    }

    // ── Status seeded from alerts.status ───────────────────────────────────

    @Test
    void aLauncherConnecting_showsItsLastSendToEachService() {
        launcher.status(new ServiceState(AlertService.NTFY, true, false, 0, Optional.of(new AlertResult(3,
                AlertService.NTFY, false, "clientLost", AlertResult.Outcome.DELIVERED, "", "", 1,
                Duration.ofMillis(90), clock.instant()))));

        integrations.onAvailable();
        runBackground();

        LastSend last = status.last(AlertService.NTFY).orElseThrow();
        assertAll(() -> assertTrue(last.result().isDelivered()),
                () -> assertEquals("Client stopped responding", last.subject()));
    }

    private AlertResult result(long id, boolean isTest) {
        return new AlertResult(id, AlertService.DISCORD, isTest, "test", AlertResult.Outcome.DELIVERED, "", "", 1,
                Duration.ofMillis(150), clock.instant());
    }
}
