package com.botwithus.bot.core.launcher;

import com.botwithus.bot.api.script.LauncherEvent;
import com.botwithus.bot.api.script.LauncherException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The process's one connection to the service: registration, the events it
 * delivers, reconnecting, and how it notices a dead service.
 */
class LauncherServiceTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final long HOST_PID = 4321;
    /** Fast pacing, so a test reconnects in milliseconds. */
    private static final LauncherService.Timings FAST = new LauncherService.Timings(
            List.of(Duration.ofMillis(10), Duration.ofMillis(20)), Duration.ofMillis(30),
            Duration.ofMillis(100), Duration.ofMillis(400), Duration.ofMillis(1));

    private final FakeLauncherService fake = new FakeLauncherService();
    private final List<CloseRequest> closeRequests = new CopyOnWriteArrayList<>();
    private final List<LauncherEvent> events = new CopyOnWriteArrayList<>();
    private final AtomicBoolean isUserStopped = new AtomicBoolean();
    private LauncherService service;

    @AfterEach
    void close() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void registers_withAHostHelloThenSubscribesToEveryAutomationTopic() {
        start();
        List<FakeLauncherService.Received> first = fake.received().subList(0, 2);
        Value hello = first.get(0).body();
        assertAll(
                () -> assertEquals(LauncherProtocol.METHOD_HELLO, first.get(0).method()),
                () -> assertEquals(Optional.of("host"), WireValues.string(hello, "clientKind")),
                () -> assertEquals(Optional.of("java"), WireValues.string(hello, "hostKind")),
                () -> assertEquals(HOST_PID, WireValues.integer(hello, "hostPid", 0L)),
                () -> assertEquals(Optional.of("BotWithUs Java host"), WireValues.string(hello, "hostLabel")),
                () -> assertEquals(LauncherProtocol.METHOD_EVENTS_SUBSCRIBE, first.get(1).method()),
                () -> assertEquals(List.of("clients", "agent", "data", "licence", "service"),
                        topics(first.get(1).body())));
    }

    @Test
    void closeRequest_isDeliveredBeforeAnySubscription() {
        fake.afterHello(() -> fake.pushEvent(LauncherProtocol.EVENT_HOST_CLOSE_REQUESTED,
                FakeLauncherService.body().put("requestId", 17).put("reason", "data_update")
                        .put("hostsBlocking", 2).build()));
        fake.handle(LauncherProtocol.METHOD_EVENTS_SUBSCRIBE, body -> new FakeLauncherService.Reply.Silence());
        service = newService();
        service.start();
        FakeLauncherService.await("the close request", WAIT, () -> !closeRequests.isEmpty());
        assertEquals(new CloseRequest(17, "data_update", 2), closeRequests.getFirst());
    }

    @Test
    void ackClose_sendsTheDecision() {
        fake.handle(LauncherProtocol.METHOD_HOST_ACK_CLOSE, body -> FakeLauncherService.ok(ValueFactory.emptyMap()));
        start();
        service.ackClose(17, CloseDecision.LATER);
        Value ack = fake.received(LauncherProtocol.METHOD_HOST_ACK_CLOSE).getFirst().body();
        assertEquals(Optional.of("later"), WireValues.string(ack, "decision"));
        assertEquals(17, WireValues.integer(ack, "requestId", 0L));
    }

    @Test
    void firstRegistration_emitsNoRestored() throws InterruptedException {
        start();
        Thread.sleep(FAST.keepalive().multipliedBy(2));
        assertEquals(List.of(), events);
    }

    @Test
    void serviceRestart_lostOnceThenRegistersAgainAndRestored() {
        start();
        fake.goDown();
        FakeLauncherService.await("the loss", WAIT, () -> !events.isEmpty());
        fake.comeBack();
        FakeLauncherService.await("re-registration", WAIT, () -> service.registrationCount() == 2);
        FakeLauncherService.await("restored", WAIT, () -> events.size() == 2);
        assertEquals(List.of(new LauncherEvent.ServiceLost(LauncherException.SERVICE_UNAVAILABLE),
                new LauncherEvent.ServiceRestored()), events);
        assertEquals(2, fake.received(LauncherProtocol.METHOD_HELLO).size());
    }

    @Test
    void whileDown_callsFailUnavailable_orStoppedWhenTheFlagIsSet() {
        start();
        fake.goDown();
        FakeLauncherService.await("the loss", WAIT, () -> !service.isConnected());
        assertEquals(LauncherException.SERVICE_UNAVAILABLE, callStatus().code());
        isUserStopped.set(true);
        assertEquals(LauncherException.SERVICE_STOPPED, callStatus().code());
    }

    @Test
    void neverReached_callsFailUnavailable_andNoLossIsReported() throws InterruptedException {
        fake.goDown();
        service = newService();
        service.start();
        Thread.sleep(FAST.steady().multipliedBy(3));
        assertAll(() -> assertEquals(LauncherException.SERVICE_UNAVAILABLE, callStatus().code()),
                () -> assertEquals(List.of(), events),
                () -> assertTrue(fake.opens() == 0));
    }

    @Test
    void unansweredCall_failsUnavailable_andTheHostReconnects() {
        start();
        fake.handle(LauncherProtocol.METHOD_CLIENT_LIST, body -> new FakeLauncherService.Reply.Silence());
        LauncherException e = assertThrows(LauncherException.class,
                () -> service.call(LauncherProtocol.METHOD_CLIENT_LIST, Envelope.emptyBody()));
        assertEquals(LauncherException.SERVICE_UNAVAILABLE, e.code());
        FakeLauncherService.await("re-registration", WAIT, () -> service.registrationCount() == 2);
    }

    @Test
    void deadButOpenPipe_isNoticedByTheKeepalive() {
        start();
        fake.freeze();
        FakeLauncherService.await("the loss", WAIT, () -> !events.isEmpty());
        fake.thaw();
        FakeLauncherService.await("re-registration", WAIT, () -> service.registrationCount() == 2);
        assertTrue(fake.received(LauncherProtocol.METHOD_SERVICE_STATUS).size() >= 1, "a keepalive probe was sent");
    }

    @Test
    void sequenceGap_reportsDroppedAndSubscribesAgain() {
        start();
        fake.pushEvent(LauncherProtocol.EVENT_AGENT_UPDATED, FakeLauncherService.body().put("sha", "a").build());
        fake.pushEventWithSeq(LauncherProtocol.EVENT_AGENT_UPDATED,
                FakeLauncherService.body().put("sha", "b").build(), 4);
        FakeLauncherService.await("the events", WAIT, () -> events.size() == 3);
        FakeLauncherService.await("a second subscribe", WAIT,
                () -> fake.received(LauncherProtocol.METHOD_EVENTS_SUBSCRIBE).size() == 2);
        assertEquals(List.of(new LauncherEvent.AgentUpdated("a"), new LauncherEvent.EventsDropped(2),
                new LauncherEvent.AgentUpdated("b")), events);
    }

    @Test
    void unknownEvent_isIgnored() throws InterruptedException {
        start();
        fake.pushEvent("future.thing", ValueFactory.emptyMap());
        fake.pushEvent(LauncherProtocol.EVENT_AGENT_UPDATED, FakeLauncherService.body().put("sha", "a").build());
        FakeLauncherService.await("the known event", WAIT, () -> !events.isEmpty());
        assertEquals(List.of(new LauncherEvent.AgentUpdated("a")), events);
    }

    @Test
    void refusedHello_isRetried() {
        fake.handle(LauncherProtocol.METHOD_HELLO, body -> FakeLauncherService.error("caller_rejected"));
        service = newService();
        service.start();
        FakeLauncherService.await("several attempts", WAIT, () -> fake.opens() >= 3);
        assertEquals(0, service.registrationCount());
    }

    @Test
    void defaultTimings_areTheAdrBackoffAndTheLeadsTimeout() {
        LauncherService.Timings t = LauncherService.Timings.DEFAULT;
        assertAll(() -> assertEquals(Duration.ofSeconds(1), t.delayBefore(0)),
                () -> assertEquals(Duration.ofSeconds(2), t.delayBefore(1)),
                () -> assertEquals(Duration.ofSeconds(4), t.delayBefore(2)),
                () -> assertEquals(Duration.ofSeconds(8), t.delayBefore(3)),
                () -> assertEquals(Duration.ofSeconds(15), t.delayBefore(4)),
                () -> assertEquals(Duration.ofSeconds(15), t.delayBefore(100)),
                () -> assertEquals(Duration.ofSeconds(5), t.keepalive()),
                () -> assertEquals(Duration.ofSeconds(40), t.callTimeout()));
    }

    private void start() {
        service = newService();
        service.start();
        FakeLauncherService.await("registration", WAIT, () -> service.isConnected());
    }

    private LauncherService newService() {
        LauncherService s = new LauncherService(fake.opener(),
                new HelloParams("test", HOST_PID, Optional.of("BotWithUs Java host")), isUserStopped::get, FAST,
                closeRequests::add);
        s.addListener(events::add);
        return s;
    }

    private LauncherException callStatus() {
        return assertThrows(LauncherException.class,
                () -> service.call(LauncherProtocol.METHOD_SERVICE_STATUS, Envelope.emptyBody()));
    }

    private static List<String> topics(Value body) {
        return WireValues.array(body, "topics").stream().map(v -> v.asStringValue().asString()).toList();
    }
}
