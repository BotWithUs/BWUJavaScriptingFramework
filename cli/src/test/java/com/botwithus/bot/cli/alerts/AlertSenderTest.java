package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.AlertDelivery;
import com.botwithus.bot.core.alerts.AlertMessage;
import com.botwithus.bot.core.alerts.AlertResult;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.DeliveryOutcome;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How finished alerts reach the launcher, wait for it, and what the status board learns. */
class AlertSenderTest {

    private static final Instant NOON = Instant.parse("2026-11-21T12:00:00Z");
    private static final String SUBJECT = "Hollowmere stopped responding";

    private final ManualClock clock = new ManualClock(NOON);
    private final FakeLauncher launcher = new FakeLauncher(clock);
    private final ServiceStatusBoard status = new ServiceStatusBoard();
    private final AlertSender sender = new AlertSender(launcher, status, Runnable::run, clock);

    AlertSenderTest() {
        launcher.addListener(sender);
    }

    private static AlertDelivery lost(AlertService... services) {
        return AlertDelivery.compose("clientLost", new AlertMessage("Client stopped responding", SUBJECT, true),
                Set.of(services), NOON);
    }

    @Test
    void aDelivery_isHandedOverOnce_asComposed() {
        AlertDelivery delivery = lost(AlertService.NTFY, AlertService.DISCORD);

        sender.send(delivery, SUBJECT);

        assertEquals(List.of(delivery), launcher.deliveries());
    }

    @Test
    void theResult_isRecordedUnderTheSubjectTheHostGaveIt() {
        sender.send(lost(AlertService.NTFY), SUBJECT);

        launcher.reportAll();

        LastSend last = status.last(AlertService.NTFY).orElseThrow();
        assertAll(() -> assertEquals(SUBJECT, last.subject()),
                () -> assertTrue(last.result().isDelivered()),
                () -> assertFalse(last.isTest()));
    }

    @Test
    void aFailedResult_isRecordedAsFailed() {
        launcher.answer(AlertService.SLACK, AlertResult.Outcome.FAILED);
        sender.send(lost(AlertService.SLACK), SUBJECT);

        launcher.reportAll();

        assertEquals("429 · Too many requests", status.last(AlertService.SLACK).orElseThrow().result().summary());
    }

    @Test
    void notConfigured_saysWhatToFillIn_perService() {
        launcher.drop(AlertService.SLACK, DeliveryOutcome.DropReason.NOT_CONFIGURED)
                .drop(AlertService.NTFY, DeliveryOutcome.DropReason.NOT_CONFIGURED);

        sender.send(lost(AlertService.SLACK, AlertService.NTFY), SUBJECT);

        assertAll(() -> assertEquals("Not set up · Add the webhook URL first",
                        status.last(AlertService.SLACK).orElseThrow().result().summary()),
                () -> assertEquals("Not set up · Add the topic first",
                        status.last(AlertService.NTFY).orElseThrow().result().summary()),
                () -> assertEquals(0, sender.waitingCount(), "an answer is final; nothing is retried"));
    }

    @Test
    void queueFull_isRecordedAsNotSent() {
        launcher.drop(AlertService.DISCORD, DeliveryOutcome.DropReason.QUEUE_FULL);

        sender.send(lost(AlertService.DISCORD), SUBJECT);

        assertEquals("Not sent · " + AlertSender.QUEUE_FULL,
                status.last(AlertService.DISCORD).orElseThrow().result().summary());
    }

    @Test
    void aDuplicate_isNotAFailure_andRecordsNothing() {
        launcher.drop(AlertService.DISCORD, DeliveryOutcome.DropReason.DUPLICATE);

        sender.send(lost(AlertService.DISCORD), SUBJECT);

        assertAll(() -> assertTrue(status.last(AlertService.DISCORD).isEmpty()),
                () -> assertEquals(0, sender.waitingCount()));
    }

    @Test
    void anOlderLauncher_isHandedNothing_andTheAlertWaits() {
        launcher.predatingAlerts();

        sender.send(lost(AlertService.NTFY), SUBJECT);

        assertAll(() -> assertEquals(List.of(), launcher.deliveries()),
                () -> assertEquals(1, sender.waitingCount()),
                () -> assertTrue(status.last(AlertService.NTFY).isEmpty()));
    }

    @Test
    void noLauncher_theAlertWaits_andGoesOutWhenOneThatSendsAlertsConnects() {
        launcher.gone();
        AlertDelivery delivery = lost(AlertService.NTFY);
        sender.send(delivery, SUBJECT);

        launcher.comeBack();

        assertAll(() -> assertEquals(List.of(delivery), launcher.deliveries()),
                () -> assertEquals(0, sender.waitingCount()));
    }

    @Test
    void aReplay_afterNoAnswer_carriesTheSameKeyAndTime() {
        launcher.leaveUnanswered(1);
        sender.send(lost(AlertService.NTFY), SUBJECT);
        clock.advance(Duration.ofSeconds(30));

        launcher.comeBack();

        List<AlertDelivery> sent = launcher.deliveries();
        assertAll(() -> assertEquals(2, sent.size(), "handed over, unanswered, handed over again"),
                () -> assertEquals(sent.get(0).dedupeKey(), sent.get(1).dedupeKey(),
                        "so the launcher drops the copy if the first did arrive"),
                () -> assertEquals(sent.get(0).at(), sent.get(1).at(), "and judges staleness from the first"));
    }

    @Test
    void waitingAlerts_goOutInOrder() {
        launcher.gone();
        AlertDelivery first = lost(AlertService.NTFY);
        AlertDelivery second = AlertDelivery.compose("clientBack", new AlertMessage("Client came back",
                "Hollowmere is back", false), Set.of(AlertService.NTFY), NOON.plusSeconds(1));
        sender.send(first, SUBJECT);
        sender.send(second, "Hollowmere is back");

        launcher.comeBack();

        assertEquals(List.of(first, second), launcher.deliveries());
    }

    @Test
    void theWaitingList_isBounded_droppingTheOldest() {
        launcher.gone();
        for (int i = 0; i <= AlertSender.MAX_WAITING; i++) {
            sender.send(AlertDelivery.compose("clientLost", new AlertMessage("Client stopped responding",
                    "client " + i, true), Set.of(AlertService.NTFY), NOON.plusSeconds(i)), SUBJECT);
        }

        launcher.comeBack();

        assertAll(() -> assertEquals(AlertSender.MAX_WAITING, launcher.deliveries().size()),
                () -> assertEquals("client 1", launcher.deliveries().getFirst().message().body()));
    }

    @Test
    void aTestResult_isLeftToTheIntegrations() {
        launcher.report(new AlertResult(9, AlertService.NTFY, true, "test", AlertResult.Outcome.DELIVERED, "", "", 1,
                Duration.ofMillis(80), NOON));

        assertTrue(status.last(AlertService.NTFY).isEmpty());
    }

    @Test
    void aResultThisHostDidNotSend_isNamedFromItsKind() {
        launcher.report(new AlertResult(9, AlertService.NTFY, false, AlertDelivery.KIND_BURST,
                AlertResult.Outcome.DELIVERED, "", "", 1, Duration.ofMillis(80), NOON));

        assertEquals(AlertSender.SEVERAL_ALERTS, status.last(AlertService.NTFY).orElseThrow().subject());
    }
}
