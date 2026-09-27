package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.cli.settings.AlertSettingKeys;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.QuietMode;
import com.botwithus.bot.cli.settings.Subscription;
import com.botwithus.bot.core.alerts.Alert;
import com.botwithus.bot.core.alerts.AlertKind;
import com.botwithus.bot.core.alerts.AlertMessage;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.SendResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertDispatcherTest {

    private static final Instant NOON = Instant.parse("2026-09-26T12:00:00Z");
    private static final String PIPE = "BotWithUs_4312";

    @TempDir
    Path dir;

    private final ManualClock clock = new ManualClock(NOON);
    private final ManualScheduler scheduler = new ManualScheduler(clock);
    private final RecordingNotifiers notifiers = new RecordingNotifiers(clock);
    private final ServiceStatusBoard status = new ServiceStatusBoard();
    private final FakeClientDirectory directory = new FakeClientDirectory().name(PIPE, "Hollowmere");
    private HostSettings settings;
    private AlertDispatcher dispatcher;
    private Subscription resumed;

    @BeforeEach
    void open() {
        settings = HostSettings.open(dir);
        dispatcher = dispatcherWith(Runnable::run);
        resumed = dispatcher.resumeHeld();
    }

    @AfterEach
    void close() {
        resumed.close();
        dispatcher.close();
        settings.close();
    }

    private AlertDispatcher dispatcherWith(Executor intake) {
        return dispatcherWith(intake, scheduler);
    }

    /** A dispatcher over the same settings and held-alerts file, as a host started again would build. */
    private AlertDispatcher dispatcherWith(Executor intake, ManualScheduler timer) {
        return new AlertDispatcher(new AlertSettings(settings), new AlertClassifier(directory), notifiers,
                status, timer, intake, DeliveryLanes.direct(), HeldAlerts.open(heldFile()), clock, ZoneOffset.UTC);
    }

    private Path heldFile() {
        return dir.resolve(HeldAlerts.FILE_NAME);
    }

    private void enable(AlertService service) {
        settings.set(AlertSettingKeys.enabled(service), true);
    }

    private void noBursts() {
        settings.set(AlertSettingKeys.BURST_SECONDS, 0L);
    }

    private Alert alert(AlertKind kind, String headline) {
        return new Alert(kind, headline, clock.instant());
    }

    @Test
    void everythingIsOffByDefault() {
        dispatcher.dispatch(alert(AlertKind.SCRIPT_CRASH, "Fishing crashed on Hollowmere"));
        scheduler.advance(Duration.ofMinutes(5));

        for (AlertService service : AlertService.values()) {
            assertEquals(List.of(), notifiers.sentTo(service), service.label());
        }
    }

    @Test
    void routing_followsTheGridPerService() {
        enable(AlertService.NTFY);
        enable(AlertService.DISCORD);
        noBursts();
        settings.set(AlertSettingKeys.route(AlertService.DISCORD, AlertKind.SCRIPT_CRASH), false);
        settings.set(AlertSettingKeys.route(AlertService.DISCORD, AlertKind.SCRIPT_STALL), true);

        dispatcher.dispatch(alert(AlertKind.SCRIPT_CRASH, "Fishing crashed on Hollowmere"));
        dispatcher.dispatch(alert(AlertKind.SCRIPT_STALL, "Fishing stalled on Hollowmere"));

        assertEquals(List.of("Script crashed"), titles(AlertService.NTFY), "ntfy: crash ticked, stall not");
        assertEquals(List.of("Script stalled"), titles(AlertService.DISCORD), "discord: stall ticked, crash not");
        assertEquals(List.of(), notifiers.sentTo(AlertService.SLACK), "slack is off");
    }

    @Test
    void routing_aTickedKindForASwitchedOffService_sendsNothing() {
        noBursts();
        settings.set(AlertSettingKeys.route(AlertService.SLACK, AlertKind.CLIENT_BACK), true);

        dispatcher.dispatch(alert(AlertKind.CLIENT_BACK, "Hollowmere is back"));

        assertEquals(List.of(), notifiers.sentTo(AlertService.SLACK));
    }

    @Test
    void burst_holdsAlertsUntilTheWindowCloses_thenSendsOneMessage() {
        enable(AlertService.NTFY);
        settings.set(AlertSettingKeys.BURST_SECONDS, 30L);

        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, "Hollowmere stopped responding"));
        scheduler.advance(Duration.ofSeconds(10));
        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, "Ravenmoor stopped responding"));
        scheduler.advance(Duration.ofSeconds(19));
        assertEquals(List.of(), notifiers.sentTo(AlertService.NTFY), "window still open");

        scheduler.advance(Duration.ofSeconds(1));

        assertEquals(List.of(new AlertMessage("2 alerts",
                "12:00 Hollowmere stopped responding\n12:00 Ravenmoor stopped responding", true)),
                notifiers.sentTo(AlertService.NTFY));
    }

    @Test
    void burst_ofOneAlert_isSentAsThatAlert() {
        enable(AlertService.NTFY);

        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, "Hollowmere stopped responding"));
        scheduler.advance(Duration.ofSeconds(30));

        assertEquals(List.of("Client stopped responding"), titles(AlertService.NTFY));
    }

    @Test
    void burst_eachServiceGroupsOnlyWhatItIsSent() {
        enable(AlertService.NTFY);
        enable(AlertService.DISCORD);
        settings.set(AlertSettingKeys.route(AlertService.DISCORD, AlertKind.CLIENT_LOST), false);

        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, "Hollowmere stopped responding"));
        dispatcher.dispatch(alert(AlertKind.SCRIPT_CRASH, "Fishing crashed on Ravenmoor"));
        scheduler.advance(Duration.ofSeconds(30));

        assertEquals(List.of("2 alerts"), titles(AlertService.NTFY));
        assertEquals(List.of("Script crashed"), titles(AlertService.DISCORD));
    }

    @Test
    void burst_zeroSeconds_sendsAtOnce() {
        enable(AlertService.NTFY);
        noBursts();

        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, "Hollowmere stopped responding"));

        assertEquals(1, notifiers.sentTo(AlertService.NTFY).size());
        assertEquals(List.of(), scheduler.pending());
    }

    @Test
    void dailySummary_isNeverHeldInABurst() {
        enable(AlertService.DISCORD);
        settings.set(AlertSettingKeys.route(AlertService.DISCORD, AlertKind.DAILY_SUMMARY), true);

        dispatcher.dispatch(new Alert(AlertKind.DAILY_SUMMARY, "1 of 1 clients online", "x", NOON));

        assertEquals(List.of("Daily summary"), titles(AlertService.DISCORD));
    }

    @Test
    void quietHours_holdBackEverythingButCrashes() {
        enable(AlertService.NTFY);
        noBursts();
        quietHours("22:00", "07:00");
        clock.set(Instant.parse("2026-09-26T23:30:00Z"));

        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, "Hollowmere stopped responding"));
        dispatcher.dispatch(alert(AlertKind.SCRIPT_CRASH, "Fishing crashed on Hollowmere"));

        assertEquals(List.of("Script crashed"), titles(AlertService.NTFY));
    }

    @Test
    void quietHours_wrapPastMidnight() {
        enable(AlertService.NTFY);
        noBursts();
        quietHours("22:00", "07:00");
        settings.set(AlertSettingKeys.QUIET_MODE, QuietMode.MUTE);

        clock.set(Instant.parse("2026-09-27T03:00:00Z"));
        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, "held"));
        clock.set(Instant.parse("2026-09-27T07:00:00Z"));
        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, "sent"));

        assertEquals(List.of("sent"), bodies(AlertService.NTFY));
    }

    @Test
    void quietHours_off_letsEverythingThrough() {
        enable(AlertService.NTFY);
        noBursts();
        settings.set(AlertSettingKeys.QUIET_FROM, "22:00");
        settings.set(AlertSettingKeys.QUIET_TO, "07:00");
        clock.set(Instant.parse("2026-09-26T23:30:00Z"));

        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, "Hollowmere stopped responding"));

        assertEquals(1, notifiers.sentTo(AlertService.NTFY).size());
    }

    @Test
    void aSend_isRecordedOnTheStatusBoard() {
        enable(AlertService.NTFY);
        noBursts();

        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, "Hollowmere stopped responding"));

        LastSend last = status.last(AlertService.NTFY).orElseThrow();
        assertEquals("Hollowmere stopped responding", last.subject());
        assertTrue(last.result().isDelivered());
        assertEquals(ServiceStatus.WORKING, status.status(AlertService.NTFY, true));
    }

    @Test
    void aServiceNotSetUp_recordsWhyAndSendsNothing() {
        enable(AlertService.SLACK);
        noBursts();
        notifiers.notReady(AlertService.SLACK, "Add the webhook URL first");

        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, "Hollowmere stopped responding"));

        SendResult result = status.last(AlertService.SLACK).orElseThrow().result();
        assertEquals("Not set up · Add the webhook URL first", result.summary());
        assertEquals(ServiceStatus.FAILED, status.status(AlertService.SLACK, true));
    }

    @Test
    void accept_handsTheEventOffBeforeDoingAnything() {
        Deque<Runnable> queued = new ArrayDeque<>();
        AlertDispatcher queuedDispatcher = dispatcherWith(queued::add);
        enable(AlertService.NTFY);
        noBursts();

        queuedDispatcher.accept(new HostEvent.ConnectionLost(new ClientRef(PIPE), null, NOON));

        assertEquals(List.of(), notifiers.sentTo(AlertService.NTFY), "nothing on the publishing thread");
        assertEquals(1, queued.size());
        queued.poll().run();
        assertEquals(List.of("Hollowmere stopped responding"), bodies(AlertService.NTFY));
    }

    @Test
    void accept_eventThatIsNoAlert_sendsNothing() {
        enable(AlertService.NTFY);
        noBursts();

        dispatcher.accept(new HostEvent.ClientOpened(new ClientRef(PIPE), NOON));

        assertEquals(List.of(), notifiers.sentTo(AlertService.NTFY));
    }

    private void quietHours(String from, String to) {
        settings.set(AlertSettingKeys.QUIET_ENABLED, true);
        settings.set(AlertSettingKeys.QUIET_FROM, from);
        settings.set(AlertSettingKeys.QUIET_TO, to);
    }

    private List<String> titles(AlertService service) {
        return notifiers.sentTo(service).stream().map(AlertMessage::title).toList();
    }

    private List<String> bodies(AlertService service) {
        return notifiers.sentTo(service).stream().map(AlertMessage::body).toList();
    }
}
