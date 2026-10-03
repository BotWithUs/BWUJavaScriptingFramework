package com.botwithus.bot.core.launcher;

import com.botwithus.bot.api.script.LaunchHandle;
import com.botwithus.bot.api.script.LaunchOptions;
import com.botwithus.bot.api.script.LaunchOutcome;
import com.botwithus.bot.api.script.LauncherEvent;
import com.botwithus.bot.api.script.LauncherException;
import com.botwithus.bot.api.script.StopMode;
import com.botwithus.bot.core.runtime.ConnectionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Launch handles, the attach callback, and what reaches a script's listener. */
class ClientLauncherImplTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String ACCOUNT = "4f1c0b8e9a7d6c5b4a3928170615f4e3";
    private static final int PID = 12345;
    private static final LauncherService.Timings FAST = new LauncherService.Timings(
            List.of(Duration.ofMillis(10)), Duration.ofMillis(20), Duration.ofMillis(100), Duration.ofMillis(400),
            Duration.ofMillis(1));

    private final FakeLauncherService fake = new FakeLauncherService();
    private final AtomicReference<String> clientState = new AtomicReference<>("queued");
    private final List<Integer> attachedPids = new CopyOnWriteArrayList<>();
    private LauncherService service;
    private ClientLauncherImpl launcher;

    @BeforeEach
    void serveOneClient() {
        fake.handle(LauncherProtocol.METHOD_CLIENT_LAUNCH,
                body -> FakeLauncherService.ok(FakeLauncherService.body().put("clientId", "c7").build()));
        fake.handle(LauncherProtocol.METHOD_CLIENT_STATUS, body -> FakeLauncherService.ok(client("c7")));
        fake.handle(LauncherProtocol.METHOD_CLIENT_STOP, body -> FakeLauncherService.ok(ValueFactory.emptyMap()));
        fake.handle(LauncherProtocol.METHOD_CLIENT_LIST, body -> FakeLauncherService.ok(FakeLauncherService.body()
                .put("clients", ValueFactory.newArray(client("c7"))).build()));
    }

    @AfterEach
    void close() {
        if (launcher != null) {
            launcher.close();
        }
        if (service != null) {
            service.close();
        }
    }

    @Test
    void injected_attachesWithThePid_andCompletesAttached() throws Exception {
        start(pid -> attached(pid));
        LaunchHandle handle = launcher.launch(ACCOUNT, LaunchOptions.defaults());
        clientState.set("injected");
        fake.pushEvent(LauncherProtocol.EVENT_CLIENT_STATE, stateEvent("c7", "injected"));
        LaunchOutcome outcome = outcomeOf(handle);
        assertAll(() -> assertEquals("c7", handle.clientId()),
                () -> assertEquals(new LaunchOutcome.Attached("BotWithUs_" + PID), outcome),
                () -> assertEquals(List.of(PID), attachedPids),
                () -> assertEquals(Optional.of(ACCOUNT), launcher.accountOf("c7")));
    }

    @Test
    void attachFailure_completesAttachFailed() throws Exception {
        start(pid -> new AttachResult.Failed("no pipe for pid " + pid));
        LaunchHandle handle = launcher.launch(ACCOUNT, LaunchOptions.defaults());
        clientState.set("injected");
        fake.pushEvent(LauncherProtocol.EVENT_CLIENT_STATE, stateEvent("c7", "injected"));
        assertEquals(new LaunchOutcome.AttachFailed("no pipe for pid " + PID), outcomeOf(handle));
    }

    @Test
    void attacherThatThrows_completesAttachFailed() throws Exception {
        start(pid -> {
            throw new IllegalStateException("boom");
        });
        LaunchHandle handle = launcher.launch(ACCOUNT, LaunchOptions.defaults());
        clientState.set("injected");
        fake.pushEvent(LauncherProtocol.EVENT_CLIENT_STATE, stateEvent("c7", "injected"));
        assertEquals(LaunchOutcome.AttachFailed.class, outcomeOf(handle).getClass());
    }

    @Test
    void failedState_completesFailedWithTheServicesCode() throws Exception {
        start(this::attached);
        LaunchHandle handle = launcher.launch(ACCOUNT, LaunchOptions.defaults());
        fake.pushEvent(LauncherProtocol.EVENT_CLIENT_STATE, FakeLauncherService.body().put("clientId", "c7")
                .put("state", "failed").put("detail", FakeLauncherService.body().put("stage", "inject")
                        .put("code", "launch_failed").put("message", "agent pipe did not open").build()).build());
        assertEquals(new LaunchOutcome.Failed("launch_failed", "agent pipe did not open"), outcomeOf(handle));
        assertEquals(List.of(), attachedPids);
    }

    @Test
    void exitBeforeInjection_completesFailedExited() throws Exception {
        start(this::attached);
        LaunchHandle handle = launcher.launch(ACCOUNT, LaunchOptions.defaults());
        fake.pushEvent(LauncherProtocol.EVENT_CLIENT_EXITED, FakeLauncherService.body().put("clientId", "c7")
                .put("exitCode", 1).put("reason", "unknown").build());
        LaunchOutcome outcome = outcomeOf(handle);
        assertEquals("exited", switch (outcome) {
            case LaunchOutcome.Failed failed -> failed.code();
            default -> "not failed: " + outcome;
        });
    }

    @Test
    void injectedWhileTheServiceWasAway_isAttachedAfterItComesBack() throws Exception {
        start(this::attached);
        LaunchHandle handle = launcher.launch(ACCOUNT, LaunchOptions.defaults());
        FakeLauncherService.await("the first status", WAIT,
                () -> fake.received(LauncherProtocol.METHOD_CLIENT_STATUS).size() == 1);
        fake.goDown();
        FakeLauncherService.await("the loss", WAIT, () -> !service.isConnected());
        clientState.set("injected");
        fake.comeBack();
        assertEquals(new LaunchOutcome.Attached("BotWithUs_" + PID), outcomeOf(handle));
    }

    @Test
    void launchGoneAfterARestart_completesFailed() throws Exception {
        start(this::attached);
        LaunchHandle handle = launcher.launch(ACCOUNT, LaunchOptions.defaults());
        FakeLauncherService.await("the first status", WAIT,
                () -> fake.received(LauncherProtocol.METHOD_CLIENT_STATUS).size() == 1);
        fake.handle(LauncherProtocol.METHOD_CLIENT_STATUS,
                body -> FakeLauncherService.error(LauncherException.CLIENT_NOT_FOUND));
        fake.breakConnection();
        LaunchOutcome outcome = outcomeOf(handle);
        assertEquals(LaunchOutcome.Failed.class, outcome.getClass());
    }

    @Test
    void launchSends_accountAndCharacter() {
        start(this::attached);
        launcher.launch(ACCOUNT, new LaunchOptions(2));
        Value body = fake.received(LauncherProtocol.METHOD_CLIENT_LAUNCH).getFirst().body();
        assertEquals(Optional.of(ACCOUNT), WireValues.string(body, "accountId"));
        assertEquals(2, WireValues.integer(body, "characterIndex", 0L));
    }

    @Test
    void stop_sendsCloseOrKill() {
        start(this::attached);
        launcher.stop("c7", StopMode.GRACEFUL);
        launcher.stop("c7", StopMode.KILL);
        List<FakeLauncherService.Received> stops = fake.received(LauncherProtocol.METHOD_CLIENT_STOP);
        assertEquals(List.of(Optional.of("close"), Optional.of("kill")),
                stops.stream().map(r -> WireValues.string(r.body(), "mode")).toList());
    }

    @Test
    void rateLimited_surfacesRetryAfter() {
        fake.handle(LauncherProtocol.METHOD_CLIENT_LAUNCH, body -> new FakeLauncherService.Reply.Error(
                "rate_limited", "Too many launches", true,
                FakeLauncherService.body().put("retryAfterMs", 20000).build()));
        start(this::attached);
        LauncherException e = assertThrows(LauncherException.class, () -> launcher.launch(ACCOUNT));
        assertAll(() -> assertEquals(LauncherException.RATE_LIMITED, e.code()),
                () -> assertEquals(OptionalLong.of(20000), e.retryAfterMs()));
    }

    @Test
    void listener_runsOffTheReaderWithTheRegisteringThreadsContext() throws Exception {
        start(this::attached);
        AtomicReference<String> seen = new AtomicReference<>();
        MDC.put("script.name", "manager");
        ConnectionContext.set("conn-a");
        AutoCloseable subscription;
        try {
            subscription = launcher.onEvent(event -> seen.set(MDC.get("script.name") + "|" + ConnectionContext.get()
                    + "|" + Thread.currentThread().getName()));
        } finally {
            MDC.remove("script.name");
            ConnectionContext.clear();
        }
        fake.pushEvent(LauncherProtocol.EVENT_AGENT_UPDATED, FakeLauncherService.body().put("sha", "a").build());
        FakeLauncherService.await("delivery", WAIT, () -> seen.get() != null);
        assertEquals("manager|conn-a|launcher-events", seen.get());
        subscription.close();
        seen.set(null);
        fake.pushEvent(LauncherProtocol.EVENT_AGENT_UPDATED, FakeLauncherService.body().put("sha", "b").build());
        Thread.sleep(FAST.keepalive().toMillis());
        assertEquals(null, seen.get());
    }

    @Test
    void clients_rememberEachClientsAccount() {
        start(this::attached);
        assertEquals(1, launcher.clients().size());
        assertEquals(Optional.of(ACCOUNT), launcher.accountOf("c7"));
        assertFalse(launcher.accountOf("c8").isPresent());
    }

    @Test
    void listenerEvent_isTheDecodedServiceEvent() {
        start(this::attached);
        List<LauncherEvent> seen = new CopyOnWriteArrayList<>();
        launcher.onEvent(seen::add);
        fake.pushEvent(LauncherProtocol.EVENT_CLIENT_EXITED, FakeLauncherService.body().put("clientId", "c9")
                .put("exitCode", 88).put("reason", "licence").build());
        FakeLauncherService.await("delivery", WAIT, () -> !seen.isEmpty());
        assertEquals(new LauncherEvent.ClientExited("c9", 88, LauncherEvent.ExitReason.LICENCE), seen.getFirst());
    }

    private void start(IntFunction<AttachResult> attacher) {
        service = new LauncherService(fake.opener(), new HelloParams("test", 1, Optional.empty()), () -> false,
                FAST, request -> { });
        launcher = new ClientLauncherImpl(service, attacher);
        service.start();
        FakeLauncherService.await("registration", WAIT, () -> service.isConnected());
    }

    private AttachResult attached(int pid) {
        attachedPids.add(pid);
        return new AttachResult.Attached("BotWithUs_" + pid);
    }

    private Value client(String clientId) {
        return FakeLauncherService.body().put("clientId", clientId).put("pid", PID).put("accountId", ACCOUNT)
                .put("accountName", "Main").put("characterIndex", -1).put("kind", "jagex").put("origin", "automation")
                .put("state", clientState.get()).put("startedAtMs", 1L).put("agentSha", "")
                .put("isAgentStale", false)
                .put("licence", FakeLauncherService.body().put("state", "ok").put("failures", 0).build()).build();
    }

    private static Value stateEvent(String clientId, String state) {
        return FakeLauncherService.body().put("clientId", clientId).put("state", state).build();
    }

    private static LaunchOutcome outcomeOf(LaunchHandle handle) throws Exception {
        return handle.outcome().toCompletableFuture().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }
}
