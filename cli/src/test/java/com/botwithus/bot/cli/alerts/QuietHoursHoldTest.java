package com.botwithus.bot.cli.alerts;

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quiet hours in {@link QuietMode#HOLD} (the default) and {@link QuietMode#MUTE}:
 * what is held, when and how it goes out, and what switching them does to it.
 * Quiet hours here run 22:00 to 07:00 UTC, and the night starts on 26 September.
 */
class QuietHoursHoldTest {

    private static final Instant LATE_EVENING = Instant.parse("2026-09-26T23:30:00Z");
    private static final String NIGHT = "2026-09-27T03:00:00Z";
    private static final String END = "2026-09-27T07:00:00Z";
    private static final String LOST = "Hollowmere stopped responding";
    private static final String STALLED = "Fishing stalled on Ravenmoor";
    private static final String CLOSED = "Ravenmoor closed";

    @TempDir
    Path dir;

    private final ManualClock clock = new ManualClock(LATE_EVENING);
    private final ManualScheduler scheduler = new ManualScheduler(clock);
    private final RecordingNotifiers notifiers = new RecordingNotifiers(clock);
    private final ServiceStatusBoard status = new ServiceStatusBoard();
    private HostSettings settings;
    private AlertDispatcher dispatcher;
    private final List<AutoCloseable> toClose = new ArrayList<>();

    @BeforeEach
    void open() {
        settings = HostSettings.open(dir);
        settings.set(AlertSettingKeys.BURST_SECONDS, 0L);
        settings.set(AlertSettingKeys.QUIET_ENABLED, true);
        settings.set(AlertSettingKeys.QUIET_FROM, "22:00");
        settings.set(AlertSettingKeys.QUIET_TO, "07:00");
        enable(AlertService.NTFY);
        dispatcher = start(scheduler);
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable closeable : toClose.reversed()) {
            closeable.close();
        }
        settings.close();
    }

    /** Builds and resumes a dispatcher over the settings and held-alerts file, as the host does on start. */
    private AlertDispatcher start(ManualScheduler timer) {
        AlertDispatcher started = new AlertDispatcher(new AlertSettings(settings),
                new AlertClassifier(new FakeClientDirectory()), notifiers, status, timer, Runnable::run,
                DeliveryLanes.direct(), HeldAlerts.open(dir.resolve(HeldAlerts.FILE_NAME)), clock, ZoneOffset.UTC);
        Subscription resumed = started.resumeHeld();
        toClose.add(started::close);
        toClose.add(resumed);
        return started;
    }

    // ── Hold, then send when quiet hours end ────────────────────────────────

    @Test
    void hold_isTheDefault() {
        assertEquals(QuietMode.HOLD, settings.get(AlertSettingKeys.QUIET_MODE));
    }

    @Test
    void hold_sendsWhatWasHeldAsOneMessagePerServiceWhenQuietHoursEnd() {
        enable(AlertService.DISCORD);
        settings.set(AlertSettingKeys.route(AlertService.DISCORD, AlertKind.SCRIPT_STALL), true);

        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, LOST));
        advanceTo(NIGHT);
        dispatcher.dispatch(alert(AlertKind.SCRIPT_STALL, STALLED));
        dispatcher.dispatch(alert(AlertKind.CLIENT_CLOSED, CLOSED));
        assertNothingSent();

        advanceTo(END);

        assertAll(
                () -> assertEquals(List.of(new AlertMessage("2 alerts during quiet hours",
                        "23:30 " + LOST + "\n03:00 " + CLOSED, true)), notifiers.sentTo(AlertService.NTFY),
                        "ntfy is not sent stalls"),
                () -> assertEquals(List.of(new AlertMessage("3 alerts during quiet hours",
                        "23:30 " + LOST + "\n03:00 " + STALLED + "\n03:00 " + CLOSED, true)),
                        notifiers.sentTo(AlertService.DISCORD)),
                () -> assertEquals(List.of(), notifiers.sentTo(AlertService.SLACK), "slack is off"));
    }

    @Test
    void hold_windowWrapsPastMidnight_sendsAtTheEndNotAtMidnight() {
        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, LOST));

        advanceTo("2026-09-27T00:30:00Z");
        advanceTo("2026-09-27T06:59:59Z");
        assertNothingSent();

        advanceTo(END);

        assertEquals(List.of("1 alert during quiet hours"), titles(AlertService.NTFY));
    }

    @Test
    void hold_aCrashStillGoesOutAtOnce_andIsNotSentAgain() {
        dispatcher.dispatch(alert(AlertKind.SCRIPT_CRASH, "Fishing crashed on Hollowmere"));
        assertEquals(List.of("Script crashed"), titles(AlertService.NTFY), "sent in quiet hours");

        advanceTo(END);

        assertEquals(List.of("Script crashed"), titles(AlertService.NTFY), "nothing was held");
        assertEquals(List.of(), scheduler.pending());
    }

    @Test
    void hold_theDailySummaryStillGoesOutAtItsTime() {
        settings.set(AlertSettingKeys.route(AlertService.NTFY, AlertKind.DAILY_SUMMARY), true);

        dispatcher.dispatch(new Alert(AlertKind.DAILY_SUMMARY, "1 of 1 clients online", "x", clock.instant()));

        assertEquals(List.of("Daily summary"), titles(AlertService.NTFY));
    }

    @Test
    void hold_many_listsTheFirstLinesAndCountsTheRest() {
        int count = AlertMessage.MAX_BURST_LINES + 5;
        for (int i = 0; i < count; i++) {
            dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, "C" + i + " stopped responding"));
        }

        advanceTo(END);

        AlertMessage only = notifiers.sentTo(AlertService.NTFY).getFirst();
        List<String> lines = only.body().lines().toList();
        assertAll(
                () -> assertEquals(1, notifiers.sentTo(AlertService.NTFY).size(), "one message"),
                () -> assertEquals(count + " alerts during quiet hours", only.title()),
                () -> assertEquals("23:30 C0 stopped responding", lines.getFirst(), "oldest first"),
                () -> assertEquals(AlertMessage.MAX_BURST_LINES + 1, lines.size()),
                () -> assertEquals("and 5 more", lines.getLast()));
    }

    @Test
    void hold_appliesTheGridAsItIsWhenQuietHoursEnd() {
        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, LOST));
        dispatcher.dispatch(alert(AlertKind.SCRIPT_STALL, STALLED));
        settings.set(AlertSettingKeys.route(AlertService.NTFY, AlertKind.CLIENT_LOST), false);
        settings.set(AlertSettingKeys.route(AlertService.NTFY, AlertKind.SCRIPT_STALL), true);
        enable(AlertService.DISCORD);

        advanceTo(END);

        assertAll(
                () -> assertEquals(List.of("23:30 " + STALLED), bodies(AlertService.NTFY)),
                () -> assertEquals(List.of("23:30 " + LOST), bodies(AlertService.DISCORD),
                        "switched on in the night, and sent what its column ticks"));
    }

    @Test
    void hold_nothingTickedAtTheEnd_sendsNothing() {
        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, LOST));
        settings.set(AlertSettingKeys.enabled(AlertService.NTFY), false);

        advanceTo(END);

        assertNothingSent();
    }

    @Test
    void hold_theSendIsRecordedOnTheStatusBoard_evenWhenItFails() {
        notifiers.answer(AlertService.NTFY,
                () -> new SendResult.Failed(clock.instant(), "429", "Too many requests", 3));
        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, LOST));

        advanceTo(END);

        LastSend last = status.last(AlertService.NTFY).orElseThrow();
        assertEquals("1 alert during quiet hours", last.subject());
        assertFalse(last.result().isDelivered());
    }

    // ── Changing the settings while alerts are held ─────────────────────────

    @Test
    void switchingQuietHoursOff_sendsWhatIsHeldNow() {
        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, LOST));

        settings.set(AlertSettingKeys.QUIET_ENABLED, false);

        assertEquals(List.of("23:30 " + LOST), bodies(AlertService.NTFY));
        assertEquals(List.of(), scheduler.pending(), "nothing left to send at the end");
        assertFalse(Files.exists(dir.resolve(HeldAlerts.FILE_NAME)), "nothing left held");
    }

    @Test
    void switchingToMute_discardsWhatIsHeld() {
        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, LOST));

        settings.set(AlertSettingKeys.QUIET_MODE, QuietMode.MUTE);
        settings.set(AlertSettingKeys.QUIET_MODE, QuietMode.HOLD);
        advanceTo(END);

        assertNothingSent();
        assertFalse(Files.exists(dir.resolve(HeldAlerts.FILE_NAME)));
    }

    @Test
    void movingTheEnd_movesWhenTheHeldAlertsGoOut() {
        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, LOST));

        settings.set(AlertSettingKeys.QUIET_TO, "06:00");

        assertEquals(List.of(Instant.parse("2026-09-27T06:00:00Z")), scheduler.pending());
        advanceTo("2026-09-27T06:00:00Z");
        assertEquals(List.of("1 alert during quiet hours"), titles(AlertService.NTFY));
    }

    @Test
    void movingTheEndToBeforeNow_sendsNow() {
        advanceTo(NIGHT);
        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, LOST));

        settings.set(AlertSettingKeys.QUIET_TO, "02:00");

        assertEquals(List.of("03:00 " + LOST), bodies(AlertService.NTFY));
    }

    // ── Mute ────────────────────────────────────────────────────────────────

    @Test
    void mute_dropsEverythingButCrashes_andSendsNothingLater() {
        settings.set(AlertSettingKeys.QUIET_MODE, QuietMode.MUTE);

        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, LOST));
        dispatcher.dispatch(alert(AlertKind.SCRIPT_CRASH, "Fishing crashed on Hollowmere"));
        advanceTo(END);

        assertEquals(List.of("Script crashed"), titles(AlertService.NTFY));
        assertFalse(Files.exists(dir.resolve(HeldAlerts.FILE_NAME)), "a muted alert is never written down");
    }

    // ── A host restart ──────────────────────────────────────────────────────

    @Test
    void restart_duringQuietHours_sendsWhatTheLastRunHeldWhenTheyEnd() throws Exception {
        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, LOST));
        closeAll();
        advanceTo(NIGHT);

        ManualScheduler afterRestart = new ManualScheduler(clock);
        start(afterRestart);
        assertNothingSent();
        afterRestart.advance(Duration.between(clock.instant(), Instant.parse(END)));

        assertEquals(List.of("23:30 " + LOST), bodies(AlertService.NTFY));
    }

    @Test
    void restart_afterQuietHoursEnded_sendsWhatTheLastRunHeldAtOnce() throws Exception {
        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, LOST));
        closeAll();
        clock.set(Instant.parse("2026-09-27T09:15:00Z"));

        start(new ManualScheduler(clock));

        assertEquals(List.of("23:30 " + LOST), bodies(AlertService.NTFY));
    }

    @Test
    void close_cancelsTheSendAtTheEnd_butKeepsWhatIsHeld() throws Exception {
        dispatcher.dispatch(alert(AlertKind.CLIENT_LOST, LOST));

        closeAll();
        advanceTo(END);

        assertNothingSent();
        assertTrue(Files.exists(dir.resolve(HeldAlerts.FILE_NAME)), "kept for the next run");
    }

    private void closeAll() throws Exception {
        for (AutoCloseable closeable : toClose.reversed()) {
            closeable.close();
        }
        toClose.clear();
    }

    private void enable(AlertService service) {
        settings.set(AlertSettingKeys.enabled(service), true);
    }

    private Alert alert(AlertKind kind, String headline) {
        return new Alert(kind, headline, clock.instant());
    }

    private void advanceTo(String instant) {
        scheduler.advance(Duration.between(clock.instant(), Instant.parse(instant)));
    }

    private void assertNothingSent() {
        for (AlertService service : AlertService.values()) {
            assertEquals(List.of(), notifiers.sentTo(service), service.label());
        }
    }

    private List<String> titles(AlertService service) {
        return notifiers.sentTo(service).stream().map(AlertMessage::title).toList();
    }

    private List<String> bodies(AlertService service) {
        return notifiers.sentTo(service).stream().map(AlertMessage::body).toList();
    }
}
