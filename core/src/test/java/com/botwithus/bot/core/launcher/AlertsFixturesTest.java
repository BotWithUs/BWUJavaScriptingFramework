package com.botwithus.bot.core.launcher;

import com.botwithus.bot.core.alerts.AlertResult;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.DeliveryOutcome;
import com.botwithus.bot.core.alerts.IntegrationReply;
import com.botwithus.bot.core.alerts.IntegrationState;
import com.botwithus.bot.core.alerts.ServiceState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@code alerts.*} answer and event the launcher sends, read from the vendored
 * golden fixtures by this host's decoder. The requests this host sends are held to
 * the fixture bytes in {@link LauncherFixturesTest}.
 */
class AlertsFixturesTest {

    private static final LauncherFixtures FIXTURES = LauncherFixtures.load();
    private static final String DISCORD_HINT = "discord.com/…";
    private static final String NTFY_HINT = "************";
    private static final long QUEUED_ID = 40;
    private static final long TEST_ID = 43;
    private static final int NTFY_QUEUED = 2;
    private static final AlertResult DELIVERED = new AlertResult(41, AlertService.NTFY, false, "scriptCrash",
            AlertResult.Outcome.DELIVERED, "", "", 1, Duration.ofMillis(212), Instant.ofEpochMilli(1795132800400L));
    private static final AlertResult FAILED_TEST = new AlertResult(42, AlertService.DISCORD, true, "test",
            AlertResult.Outcome.FAILED, "404 Not Found", "Unknown Webhook", 1, Duration.ofMillis(180),
            Instant.ofEpochMilli(1795132800900L));

    private static Envelope response(String name) {
        Envelope e = FIXTURES.envelope(name);
        assertEquals(Envelope.Kind.RESPONSE, e.kind(), name);
        return e;
    }

    private static Envelope event(String name) {
        Envelope e = FIXTURES.envelope(name);
        assertEquals(Envelope.Kind.EVENT, e.kind(), name);
        assertEquals(LauncherProtocol.EVENT_ALERTS_RESULT, e.method(), name);
        return e;
    }

    @Test
    void deliverResponse_readsQueuedWithItsId_andDroppedWithItsReason() {
        assertEquals(List.of(
                new DeliveryOutcome(AlertService.DISCORD, true, OptionalLong.of(QUEUED_ID), Optional.empty()),
                new DeliveryOutcome(AlertService.NTFY, false, OptionalLong.empty(),
                        Optional.of(DeliveryOutcome.DropReason.NOT_CONFIGURED))),
                AlertsWire.deliverOutcomes(response("alerts.deliver.resp").body()));
    }

    @Test
    void integrationsGetResponse_readsEveryServiceMasked() {
        assertEquals(List.of(
                new IntegrationState(AlertService.DISCORD, true, Optional.of(DISCORD_HINT), false, "", ""),
                new IntegrationState(AlertService.SLACK, false, Optional.empty(), false, "", ""),
                new IntegrationState(AlertService.NTFY, true, Optional.of(NTFY_HINT), false, "https://ntfy.sh",
                        "my-alerts")),
                AlertsWire.integrations(response("alerts.integrations.get.resp").body()));
    }

    @Test
    void integrationsSetResponse_readsTheUpdatedService() {
        assertEquals(Optional.of(new IntegrationState(AlertService.DISCORD, true, Optional.of(DISCORD_HINT), true,
                "", "")), AlertsWire.integration(response("alerts.integrations.set.resp").body()));
    }

    @Test
    void integrationsSetRefusal_keepsTheFieldAndTheLaunchersWords() {
        Envelope refused = response("alerts.integrations.set.refused");

        assertAll(() -> assertFalse(refused.isOk()),
                () -> assertEquals(new IntegrationReply.Refused(Optional.of("secret"),
                        "The webhook URL must start with https://"),
                        LauncherAlerts.refusal(refused.error().orElseThrow())));
    }

    @Test
    void integrationsTestResponse_readsTheDeliveryIdItsResultWillCarry() {
        assertEquals(OptionalLong.of(TEST_ID),
                AlertsWire.testDeliveryId(response("alerts.integrations.test.resp").body()));
    }

    @Test
    void statusResponse_readsEachServicesStateAndLastResult() {
        assertEquals(List.of(
                new ServiceState(AlertService.DISCORD, true, false, 0, Optional.of(FAILED_TEST)),
                new ServiceState(AlertService.SLACK, false, false, 0, Optional.empty()),
                new ServiceState(AlertService.NTFY, true, true, NTFY_QUEUED, Optional.of(DELIVERED))),
                AlertsWire.status(response("alerts.status.resp").body()));
    }

    @Test
    void resultEvent_delivered() {
        assertEquals(Optional.of(DELIVERED), AlertsWire.result(event("alerts.result.event").body()));
    }

    @Test
    void resultEvent_failed_keepsItsStatusAndReason() {
        Optional<AlertResult> result = AlertsWire.result(event("alerts.result.event.failed").body());

        assertAll(() -> assertEquals(Optional.of(FAILED_TEST), result),
                () -> assertEquals("404 Not Found · Unknown Webhook",
                        result.orElseThrow().toSendResult().summary()));
    }

    @Test
    void theAlertsResultEvent_isNoScriptEvent() {
        Envelope e = event("alerts.result.event");

        assertTrue(LauncherDecoder.event(e.method(), e.body()).isEmpty(),
                "alerts.result goes to the alert machinery through its tap, never to scripts");
    }
}
