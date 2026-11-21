package com.botwithus.bot.core.launcher;

import com.botwithus.bot.core.alerts.AlertDelivery;
import com.botwithus.bot.core.alerts.AlertGateway;
import com.botwithus.bot.core.alerts.AlertMessage;
import com.botwithus.bot.core.alerts.AlertResult;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.DeliverReply;
import com.botwithus.bot.core.alerts.DeliveryOutcome;
import com.botwithus.bot.core.alerts.IntegrationChange;
import com.botwithus.bot.core.alerts.IntegrationReply;
import com.botwithus.bot.core.alerts.IntegrationState;
import com.botwithus.bot.core.alerts.TestReply;
import com.botwithus.bot.core.secrets.Secret;
import com.botwithus.msgpack.MsgMap;
import com.botwithus.msgpack.MsgValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code alerts.*} methods through the real connection to a fake service:
 * which launcher sends alerts, what each request carries, how each answer and the
 * {@code alerts.result} event read back, and that an older launcher is never
 * asked anything.
 */
class LauncherAlertsTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final LauncherService.Timings FAST = new LauncherService.Timings(
            List.of(Duration.ofMillis(10)), Duration.ofMillis(20),
            Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofMillis(1));
    private static final Instant AT = Instant.parse("2026-11-21T09:30:00Z");
    private static final long DELIVERY_ID = 41;
    private static final long ATTEMPTS = 3;
    private static final long ELAPSED_MS = 3120;
    /** Obviously fake: nothing is ever sent to it. */
    private static final String HOOK = "https://discord.com/api/webhooks/0000/not-a-real-token";

    private final FakeLauncherService fake = new FakeLauncherService();
    private final List<AlertResult> results = new CopyOnWriteArrayList<>();
    private final AtomicInteger availableCount = new AtomicInteger();
    private final AtomicInteger unavailableCount = new AtomicInteger();
    private LauncherService service;
    private LauncherAlerts alerts;

    @AfterEach
    void close() {
        if (alerts != null) {
            alerts.close();
        }
        if (service != null) {
            service.close();
        }
    }

    // ── Which launcher sends alerts ────────────────────────────────────────

    @Test
    void aLauncherListingTheAlertMethods_sendsAlerts_andIsSubscribedToTheirTopic() {
        helloListing(LauncherProtocol.ALERT_METHODS);
        start();

        assertAll(() -> assertTrue(alerts.isAvailable()),
                () -> assertEquals(1, availableCount.get(), "listeners hear that alerts can go out"),
                () -> assertTrue(subscribedTopics().contains(LauncherProtocol.TOPIC_ALERTS)));
    }

    @Test
    void anOlderLauncher_sendsNoAlerts_isNotSubscribedToTheTopic_andIsNeverAsked() {
        helloListing(List.of(LauncherProtocol.METHOD_SERVICE_STATUS));
        start();

        DeliverReply reply = alerts.deliver(problem());

        assertAll(() -> assertFalse(alerts.isAvailable()),
                () -> assertTrue(alerts.isConnected()),
                () -> assertEquals(new DeliverReply.Unavailable(AlertGateway.NEEDS_NEWER_LAUNCHER), reply),
                () -> assertEquals(Optional.empty(), alerts.integrations()),
                () -> assertInstanceOf(TestReply.Unavailable.class, alerts.sendTest(AlertService.SLACK)),
                () -> assertFalse(subscribedTopics().contains(LauncherProtocol.TOPIC_ALERTS),
                        "a service older than the topic refuses the whole subscribe"),
                () -> assertTrue(fake.received().stream().noneMatch(r -> r.method().startsWith("alerts.")),
                        "nothing was asked of a launcher that does not send alerts"),
                () -> assertEquals(0, availableCount.get()));
    }

    @Test
    void aLauncherListingOnlySomeAlertMethods_sendsNoAlerts() {
        helloListing(List.of(LauncherProtocol.METHOD_ALERTS_DELIVER));
        start();

        assertFalse(alerts.isAvailable());
    }

    @Test
    void losingTheLauncher_isHeard() {
        helloListing(LauncherProtocol.ALERT_METHODS);
        start();

        fake.goDown();
        FakeLauncherService.await("the loss", WAIT, () -> unavailableCount.get() == 1);

        assertFalse(alerts.isAvailable());
    }

    // ── alerts.deliver ─────────────────────────────────────────────────────

    @Test
    void aProblemAlert_carriesItsSeverityWordsServicesKeyAndTime() {
        servingAlerts();
        handleDeliver();
        start();
        AlertDelivery delivery = problem();

        alerts.deliver(delivery);

        MsgValue body = onlyBody(LauncherProtocol.METHOD_ALERTS_DELIVER);
        assertAll(() -> assertEquals(List.of("kind", "severity", "title", "body", "services", "dedupeKey", "ts"),
                        keys(body), "the wire shape's order, and no fields"),
                () -> assertEquals("scriptCrash", WireValues.string(body, "kind", "")),
                () -> assertEquals("problem", WireValues.string(body, "severity", "")),
                () -> assertEquals("Script crashed", WireValues.string(body, "title", "")),
                () -> assertEquals("Fishing crashed on Hollowmere", WireValues.string(body, "body", "")),
                () -> assertEquals(List.of("discord", "ntfy"), strings(body, "services"), "in the launcher's order"),
                () -> assertEquals(delivery.dedupeKey().orElseThrow(), WireValues.string(body, "dedupeKey", "")),
                () -> assertEquals(AT.toEpochMilli(), WireValues.integer(body, "ts", 0L)));
    }

    @Test
    void anInfoAlert_isSentAsInfo() {
        servingAlerts();
        handleDeliver();
        start();

        alerts.deliver(AlertDelivery.compose("clientBack", new AlertMessage("Client came back",
                "Hollowmere is back", false), Set.of(AlertService.SLACK), AT));

        MsgValue body = onlyBody(LauncherProtocol.METHOD_ALERTS_DELIVER);
        assertAll(() -> assertEquals("info", WireValues.string(body, "severity", "")),
                () -> assertEquals(List.of("slack"), strings(body, "services")));
    }

    @Test
    void theAnswer_readsBackPerService_queuedWithAnId_orDroppedWithAReason() {
        servingAlerts();
        fake.handle(LauncherProtocol.METHOD_ALERTS_DELIVER, b -> FakeLauncherService.ok(FakeLauncherService.body()
                .put("accepted", true).put("results", MsgValue.of(List.of(
                        outcome("discord", "queued", DELIVERY_ID, ""),
                        outcome("slack", "dropped", 0, "not_configured"),
                        outcome("ntfy", "dropped", 0, "queue_full"),
                        outcome("pager", "queued", DELIVERY_ID + 1, "")))).build()));
        start();

        DeliverReply reply = alerts.deliver(problem());

        assertEquals(new DeliverReply.Answered(List.of(
                new DeliveryOutcome(AlertService.DISCORD, true, OptionalLong.of(DELIVERY_ID), Optional.empty()),
                new DeliveryOutcome(AlertService.SLACK, false, OptionalLong.empty(),
                        Optional.of(DeliveryOutcome.DropReason.NOT_CONFIGURED)),
                new DeliveryOutcome(AlertService.NTFY, false, OptionalLong.empty(),
                        Optional.of(DeliveryOutcome.DropReason.QUEUE_FULL)))), reply,
                "a service this host does not know is skipped");
    }

    @Test
    void aDuplicate_readsAsDropped_asDuplicate() {
        servingAlerts();
        fake.handle(LauncherProtocol.METHOD_ALERTS_DELIVER, b -> FakeLauncherService.ok(FakeLauncherService.body()
                .put("accepted", false).put("results", MsgValue.of(List.of(
                        outcome("ntfy", "dropped", 0, "duplicate")))).build()));
        start();

        DeliverReply.Answered reply = assertInstanceOf(DeliverReply.Answered.class, alerts.deliver(problem()));

        assertEquals(Optional.of(DeliveryOutcome.DropReason.DUPLICATE), reply.outcomes().getFirst().dropReason());
    }

    @Test
    void aBadRequest_isRejected_notKeptForLater() {
        servingAlerts();
        fake.handle(LauncherProtocol.METHOD_ALERTS_DELIVER, b -> FakeLauncherService.error("bad_request"));
        start();

        assertInstanceOf(DeliverReply.Rejected.class, alerts.deliver(problem()));
    }

    @Test
    void aTitleAndBody_areCutToTheLaunchersLimits_neverInsideACharacter() {
        String title = "é".repeat(AlertsWire.MAX_TITLE_CHARS + 1);
        String body = "€".repeat(AlertsWire.MAX_BODY_BYTES);

        String cutTitle = AlertsWire.capCodePoints(title, AlertsWire.MAX_TITLE_CHARS);
        String cutBody = AlertsWire.capUtf8(body, AlertsWire.MAX_BODY_BYTES);

        assertAll(() -> assertEquals(AlertsWire.MAX_TITLE_CHARS, cutTitle.codePointCount(0, cutTitle.length())),
                () -> assertTrue(cutBody.getBytes(StandardCharsets.UTF_8).length
                        <= AlertsWire.MAX_BODY_BYTES),
                () -> assertTrue(cutBody.chars().allMatch(c -> c == '€')));
    }

    // ── alerts.integrations.* ──────────────────────────────────────────────

    @Test
    void integrations_readMaskedInTheLaunchersOrder() {
        servingAlerts();
        fake.handle(LauncherProtocol.METHOD_ALERTS_INTEGRATIONS_GET, b -> FakeLauncherService.ok(
                FakeLauncherService.body().put("services", MsgValue.of(List.of(
                        FakeLauncherService.body().put("service", "discord").put("hasSecret", true)
                                .put("secretHint", "discord.com/…").put("mentionHere", true).build(),
                        FakeLauncherService.body().put("service", "slack").put("hasSecret", false).build(),
                        FakeLauncherService.body().put("service", "ntfy").put("hasSecret", false)
                                .put("server", "https://ntfy.sh").put("topic", "").build()))).build()));
        start();

        assertEquals(Optional.of(List.of(
                new IntegrationState(AlertService.DISCORD, true, Optional.of("discord.com/…"), true, "", ""),
                new IntegrationState(AlertService.SLACK, false, Optional.empty(), false, "", ""),
                new IntegrationState(AlertService.NTFY, false, Optional.empty(), false, "https://ntfy.sh", ""))),
                alerts.integrations());
    }

    @Test
    void aChange_sendsOnlyWhatChanges_andTheSecretOnlyThere() {
        servingAlerts();
        fake.handle(LauncherProtocol.METHOD_ALERTS_INTEGRATIONS_SET, b -> FakeLauncherService.ok(
                FakeLauncherService.body().put("service", "discord").put("hasSecret", true)
                        .put("secretHint", "discord.com/…").build()));
        start();

        IntegrationReply reply = alerts.change(IntegrationChange.secret(AlertService.DISCORD, new Secret(HOOK)));

        MsgValue body = onlyBody(LauncherProtocol.METHOD_ALERTS_INTEGRATIONS_SET);
        assertAll(() -> assertEquals(List.of("service", "secret"), keys(body)),
                () -> assertEquals(HOOK, WireValues.string(body, "secret", "")),
                () -> assertEquals(new IntegrationReply.Updated(new IntegrationState(AlertService.DISCORD, true,
                        Optional.of("discord.com/…"), false, "", "")), reply));
    }

    @Test
    void clearingTheSecret_sendsAnEmptyOne() {
        servingAlerts();
        fake.handle(LauncherProtocol.METHOD_ALERTS_INTEGRATIONS_SET, b -> FakeLauncherService.ok(
                FakeLauncherService.body().put("service", "slack").put("hasSecret", false).build()));
        start();

        alerts.change(IntegrationChange.clearSecret(AlertService.SLACK));

        assertEquals("", WireValues.string(onlyBody(LauncherProtocol.METHOD_ALERTS_INTEGRATIONS_SET), "secret",
                "absent"));
    }

    @Test
    void aRefusedChange_keepsTheFieldAndTheLaunchersWords() {
        servingAlerts();
        fake.handle(LauncherProtocol.METHOD_ALERTS_INTEGRATIONS_SET, b -> new FakeLauncherService.Reply.Error(
                "bad_request", "The webhook URL must start with https://", false,
                FakeLauncherService.body().put("field", "secret").build()));
        start();

        IntegrationReply reply = alerts.change(IntegrationChange.secret(AlertService.SLACK, new Secret("http://x")));

        assertEquals(new IntegrationReply.Refused(Optional.of("secret"), "The webhook URL must start with https://"),
                reply);
    }

    @Test
    void aTest_isQueuedWithTheIdItsResultWillCarry() {
        servingAlerts();
        fake.handle(LauncherProtocol.METHOD_ALERTS_INTEGRATIONS_TEST, b -> FakeLauncherService.ok(
                FakeLauncherService.body().put("deliveryId", DELIVERY_ID).build()));
        start();

        TestReply reply = alerts.sendTest(AlertService.NTFY);

        assertAll(() -> assertEquals(new TestReply.Queued(DELIVERY_ID), reply),
                () -> assertEquals("ntfy", WireValues.string(
                        onlyBody(LauncherProtocol.METHOD_ALERTS_INTEGRATIONS_TEST), "service", "")));
    }

    @Test
    void aTestOfAServiceNotSetUp_isRefusedWithTheLaunchersWords() {
        servingAlerts();
        fake.handle(LauncherProtocol.METHOD_ALERTS_INTEGRATIONS_TEST, b -> new FakeLauncherService.Reply.Error(
                "bad_request", "Add the topic first", false,
                FakeLauncherService.body().put("field", "service").build()));
        start();

        assertEquals(new TestReply.Refused("Add the topic first"), alerts.sendTest(AlertService.NTFY));
    }

    // ── alerts.result and alerts.status ────────────────────────────────────

    @Test
    void theResultEvent_reachesListeners_andNoScriptEvent() {
        servingAlerts();
        start();

        fake.pushEvent(LauncherProtocol.EVENT_ALERTS_RESULT, resultBody("failed", "429", "Too many requests"));
        FakeLauncherService.await("the result", WAIT, () -> !results.isEmpty());

        assertEquals(new AlertResult(DELIVERY_ID, AlertService.DISCORD, true, "test", AlertResult.Outcome.FAILED,
                "429", "Too many requests", (int) ATTEMPTS, Duration.ofMillis(ELAPSED_MS), AT), results.getFirst());
    }

    @Test
    void aStaleResult_readsAsNotSent() {
        servingAlerts();
        start();

        fake.pushEvent(LauncherProtocol.EVENT_ALERTS_RESULT, resultBody("stale", "", ""));
        FakeLauncherService.await("the result", WAIT, () -> !results.isEmpty());

        assertEquals("Not sent · it waited too long to go out", results.getFirst().toSendResult().summary());
    }

    @Test
    void status_readsEachServicesLastResult() {
        servingAlerts();
        fake.handle(LauncherProtocol.METHOD_ALERTS_STATUS, b -> FakeLauncherService.ok(FakeLauncherService.body()
                .put("services", MsgValue.of(List.of(FakeLauncherService.body().put("service", "discord")
                        .put("configured", true).put("sending", false).put("queued", 2)
                        .put("last", resultBody("delivered", "", "")).build()))).build()));
        start();

        var states = alerts.status().orElseThrow();

        assertAll(() -> assertEquals(1, states.size()),
                () -> assertTrue(states.getFirst().isConfigured()),
                () -> assertEquals(2, states.getFirst().queued()),
                () -> assertEquals(AlertResult.Outcome.DELIVERED, states.getFirst().last().orElseThrow().outcome()));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static AlertDelivery problem() {
        return AlertDelivery.compose("scriptCrash", new AlertMessage("Script crashed",
                "Fishing crashed on Hollowmere", true), Set.of(AlertService.NTFY, AlertService.DISCORD), AT);
    }

    private void servingAlerts() {
        helloListing(LauncherProtocol.ALERT_METHODS);
    }

    private void handleDeliver() {
        fake.handle(LauncherProtocol.METHOD_ALERTS_DELIVER, b -> FakeLauncherService.ok(FakeLauncherService.body()
                .put("accepted", true).put("results", MsgValue.of(List.of())).build()));
    }

    private void helloListing(List<String> methods) {
        List<MsgValue> listed = methods.stream().map(MsgValue::of).toList();
        fake.handle(LauncherProtocol.METHOD_HELLO, body -> FakeLauncherService.ok(FakeLauncherService.body()
                .put("protocolVersion", 1).put("serviceVersion", "fake").put("surface", "automation")
                .put("methods", MsgValue.of(listed)).put("events", MsgValue.of(List.of()))
                .put("connectionId", 1).build()));
    }

    private void start() {
        service = new LauncherService(fake.opener(), new HelloParams("test", 1, Optional.empty()), () -> false,
                FAST, request -> { });
        alerts = LauncherAlerts.attach(service);
        alerts.addListener(new AlertGateway.Listener() {
            @Override
            public void onAvailable() {
                availableCount.incrementAndGet();
            }

            @Override
            public void onUnavailable() {
                unavailableCount.incrementAndGet();
            }

            @Override
            public void onResult(AlertResult result) {
                results.add(result);
            }
        });
        service.start();
        FakeLauncherService.await("registration", WAIT, service::isConnected);
    }

    private List<String> subscribedTopics() {
        return strings(fake.received(LauncherProtocol.METHOD_EVENTS_SUBSCRIBE).getFirst().body(), "topics");
    }

    private MsgValue onlyBody(String method) {
        List<FakeLauncherService.Received> got = fake.received(method);
        assertEquals(1, got.size(), method);
        return got.getFirst().body();
    }

    private static List<String> strings(MsgValue map, String key) {
        return WireValues.array(map, key).stream().map(v -> v.stringValue().orElseThrow()).toList();
    }

    private static List<String> keys(MsgValue map) {
        List<String> names = new ArrayList<>();
        map.mapValue().orElseThrow().entries().forEach(e -> names.add(e.key().stringValue().orElseThrow()));
        return names;
    }

    private static MsgMap outcome(String service, String outcome, long deliveryId, String reason) {
        FakeLauncherService.Body body = FakeLauncherService.body().put("service", service).put("outcome", outcome);
        if (deliveryId > 0) {
            body.put("deliveryId", deliveryId);
        }
        if (!reason.isEmpty()) {
            body.put("reason", reason);
        }
        return body.build();
    }

    private static MsgMap resultBody(String outcome, String status, String reason) {
        FakeLauncherService.Body body = FakeLauncherService.body().put("deliveryId", DELIVERY_ID)
                .put("service", "discord").put("isTest", true).put("kind", "test").put("outcome", outcome);
        if (!status.isEmpty()) {
            body.put("status", status);
        }
        if (!reason.isEmpty()) {
            body.put("reason", reason);
        }
        return body.put("attempts", ATTEMPTS).put("elapsedMs", ELAPSED_MS).put("ts", AT.toEpochMilli()).build();
    }
}
