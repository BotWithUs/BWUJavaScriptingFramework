package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.gameui.Canvas;
import com.botwithus.bot.api.gameui.CanvasProps;
import com.botwithus.bot.api.gameui.GameUi;
import com.botwithus.bot.api.gameui.JagexComponent;
import com.botwithus.bot.api.gameui.UiEvent;
import com.botwithus.bot.api.gameui.UiHandleInfo;
import com.botwithus.bot.api.gameui.UiRefusedException;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcRemoteException;
import com.botwithus.bot.core.runtime.ManagementCallerTag;
import com.botwithus.bot.core.runtime.ScriptGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The lifetime holes an independent review found in the first cut, each pinned by a
 * test that fails without its fix: a create in flight across a release (F1), a
 * release run on a revoked script's own thread (F2), management scripts counted as
 * the host (F3), a connection reset leaving live-looking handles (F4), and a
 * refused subscribe failing a create (N1).
 *
 * <p>The mocked {@link RpcClient} calls the real {@link ScriptGate#checkCaller()} on
 * every call, as the real client does, so a call made by a revoked script's thread
 * is parked and refused here exactly as it would be in production.</p>
 */
class GameUiLifetimeTest {

    private static final String ALPHA = "Alpha";
    private static final String BETA = "Beta";
    private static final String MANAGER = ManagementCallerTag.ownerOf("Manager");
    private static final long JAGEX_HANDLE = 10L;
    private static final long FIRST_CREATED = 100L;
    private static final int IFACE = 1477;
    private static final int COMP = 61;
    private static final long AWAIT_SECONDS = 5L;
    /** Comfortably under the gate's park for one refused call, so a parked release fails the bound. */
    private static final long RELEASE_BOUND_MS = 200L;

    private RpcClient rpc;
    private ScriptGate gate;
    private ManagementCallerTag managers;
    private GameAPIImpl api;
    private GameUi ui;
    private final AtomicLong nextHandle = new AtomicLong(FIRST_CREATED);
    /** The gate tag of the thread each {@code ui.destroy} was sent from, in order. */
    private final BlockingQueue<String> destroyCallers = new LinkedBlockingQueue<>();

    @BeforeEach
    void setUp() {
        rpc = mock(RpcClient.class);
        gate = new ScriptGate();
        managers = new ManagementCallerTag();
        api = new GameAPIImpl(rpc);
        api.setScriptGate(gate);
        api.setManagementCallerTag(managers);
        ui = api.gameUi();
        when(rpc.callSync(eq("ui.get_component"), anyMap())).thenAnswer(inv -> {
            gate.checkCaller();
            return UiCodecTest.handleMap(JAGEX_HANDLE, "layer", true);
        });
        when(rpc.callSync(eq("ui.create"), anyMap())).thenAnswer(inv -> {
            gate.checkCaller();
            return UiCodecTest.handleMap(nextHandle.getAndIncrement(), "canvas", false);
        });
        when(rpc.callSync(eq("ui.destroy"), anyMap())).thenAnswer(inv -> {
            gate.checkCaller();
            destroyCallers.add(String.valueOf(gate.current()));
            return Map.of("freed", true);
        });
    }

    @AfterEach
    void tearDown() {
        gate.exit();
        managers.exit();
    }

    private <T> T asScript(String script, Supplier<T> action) {
        gate.enter(script);
        try {
            return action.get();
        } finally {
            gate.exit();
        }
    }

    private <T> T asManager(Supplier<T> action) {
        managers.enter(MANAGER);
        try {
            return action.get();
        } finally {
            managers.exit();
        }
    }

    private Canvas canvas() {
        return ui.createCanvas(ui.component(IFACE, COMP), new CanvasProps().size(1, 1));
    }

    private static Map<String, Object> handleParam(long agentHandle) {
        return Map.of("handle", agentHandle);
    }

    @Nested
    @DisplayName("F1: a create in flight across a release")
    class InFlightCreate {

        private final CountDownLatch createEntered = new CountDownLatch(1);
        private final CountDownLatch replyMayGo = new CountDownLatch(1);

        @BeforeEach
        void holdCreateReplies() {
            when(rpc.callSync(eq("ui.create"), anyMap())).thenAnswer(inv -> {
                gate.checkCaller();
                createEntered.countDown();
                assertTrue(replyMayGo.await(AWAIT_SECONDS, TimeUnit.SECONDS));
                return UiCodecTest.handleMap(FIRST_CREATED, "canvas", false);
            });
        }

        private CompletableFuture<Canvas> createInBackgroundAs(String script) throws InterruptedException {
            CompletableFuture<Canvas> created = CompletableFuture.supplyAsync(() -> asScript(script,
                    GameUiLifetimeTest.this::canvas));
            assertTrue(createEntered.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the create never reached the agent");
            return created;
        }

        private void assertDestroyedAsHost(CompletableFuture<Canvas> created) throws InterruptedException {
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> created.get(AWAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals(GameUiBinding.RELEASED, e.getCause().getMessage());
            verify(rpc).callSync("ui.destroy", handleParam(FIRST_CREATED));
            assertEquals("null", destroyCallers.poll(AWAIT_SECONDS, TimeUnit.SECONDS),
                    "the cleanup destroy must be sent as the host, untagged");
        }

        @Test
        @DisplayName("a reply landing after the owner's release is destroyed and the create fails")
        void replyAfterRelease_isDestroyed() throws InterruptedException {
            CompletableFuture<Canvas> created = createInBackgroundAs(ALPHA);

            api.releaseGameUi(ALPHA);
            replyMayGo.countDown();

            assertDestroyedAsHost(created);
        }

        @Test
        @DisplayName("a reply landing after the owner was revoked is destroyed, despite the gate")
        void replyAfterRevoke_isDestroyed() throws InterruptedException {
            CompletableFuture<Canvas> created = createInBackgroundAs(ALPHA);

            gate.revoke(ALPHA);
            replyMayGo.countDown();

            assertDestroyedAsHost(created);
        }

        @Test
        @DisplayName("positive control: a new run of the owner may create again")
        void reopenedOwner_createsNormally() {
            replyMayGo.countDown();
            api.releaseGameUi(ALPHA);

            api.openGameUi(ALPHA);

            assertEquals(FIRST_CREATED, asScript(ALPHA, GameUiLifetimeTest.this::canvas).handle());
            verify(rpc, never()).callSync(eq("ui.destroy"), anyMap());
        }
    }

    @Nested
    @DisplayName("F2: release runs as the host and survives failures")
    class ReleaseAsHost {

        @Test
        @DisplayName("released on a revoked script's own thread, every handle is still destroyed, unparked")
        void releaseOnRevokedThread_destroysEverything() {
            asScript(ALPHA, GameUiLifetimeTest.this::canvas);
            asScript(ALPHA, GameUiLifetimeTest.this::canvas);
            gate.revoke(ALPHA);

            long start = System.nanoTime();
            int removed = asScript(ALPHA, () -> api.releaseGameUi(ALPHA));
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertEquals(2, removed);
            assertTrue(tookMs < RELEASE_BOUND_MS, "release was parked by the gate: " + tookMs + " ms");
        }

        @Test
        @DisplayName("the releasing thread keeps its own tag afterwards")
        void releaseRestoresTheCallersTag() {
            asScript(ALPHA, GameUiLifetimeTest.this::canvas);

            String after = asScript(ALPHA, () -> {
                api.releaseGameUi(ALPHA);
                return gate.current();
            });

            assertEquals(ALPHA, after);
        }

        @Test
        @DisplayName("one handle failing with any RuntimeException does not stop the rest, and nothing is retried")
        void oneFailure_doesNotStopTheRest() {
            Canvas first = asScript(ALPHA, GameUiLifetimeTest.this::canvas);
            Canvas second = asScript(ALPHA, GameUiLifetimeTest.this::canvas);
            doThrow(new IllegalStateException("not an RpcException"))
                    .when(rpc).callSync("ui.destroy", handleParam(second.handle()));

            assertEquals(1, api.releaseGameUi(ALPHA));
            verify(rpc).callSync("ui.destroy", handleParam(first.handle()));

            assertEquals(0, api.releaseGameUi(ALPHA), "each handle was untracked after its attempt");
            verify(rpc, times(1)).callSync("ui.destroy", handleParam(second.handle()));
        }
    }

    @Nested
    @DisplayName("F3: a management script is an owner, not the host")
    class ManagementOwner {

        @Test
        @DisplayName("scripts and a management script cannot touch each other's components")
        void managementAndScripts_areIsolated() {
            Canvas managersCanvas = asManager(GameUiLifetimeTest.this::canvas);
            Canvas alphas = asScript(ALPHA, GameUiLifetimeTest.this::canvas);

            assertEquals(GameUiBinding.NOT_YOURS, assertThrows(UiRefusedException.class,
                    () -> asScript(ALPHA, managersCanvas::destroy)).getMessage());
            assertEquals(GameUiBinding.NOT_YOURS,
                    assertThrows(UiRefusedException.class, () -> asManager(alphas::destroy)).getMessage());
            verify(rpc, never()).callSync(eq("ui.destroy"), anyMap());
        }

        @Test
        @DisplayName("a management script lists only its own components")
        void managementList_isNarrowed() {
            Canvas mine = asManager(GameUiLifetimeTest.this::canvas);
            Canvas alphas = asScript(ALPHA, GameUiLifetimeTest.this::canvas);
            when(rpc.callSync(eq("ui.list"), anyMap())).thenReturn(Map.of("handles", List.of(
                    UiCodecTest.handleMap(mine.handle(), "canvas", false),
                    UiCodecTest.handleMap(alphas.handle(), "canvas", false))));

            List<Long> seen = asManager(() -> ui.list().stream().map(UiHandleInfo::handle).toList());

            assertEquals(List.of(mine.handle()), seen);
        }

        @Test
        @DisplayName("releasing a management script destroys its components only")
        void managementRelease_destroysItsOwn() {
            Canvas mine = asManager(GameUiLifetimeTest.this::canvas);
            Canvas alphas = asScript(ALPHA, GameUiLifetimeTest.this::canvas);

            assertEquals(1, api.releaseGameUi(MANAGER));

            verify(rpc).callSync("ui.destroy", handleParam(mine.handle()));
            verify(rpc, never()).callSync("ui.destroy", handleParam(alphas.handle()));
        }
    }

    @Nested
    @DisplayName("F4: a connection reset")
    class ConnectionReset {

        private Runnable resetListener() {
            ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
            verify(rpc).addConnectionResetListener(captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("every owner hears connection_reset for each handle it held, Jagex ones included")
        void reset_isAnnouncedToOwners() throws InterruptedException {
            Canvas alpha = asScript(ALPHA, GameUiLifetimeTest.this::canvas);
            BlockingQueue<UiEvent> heard = new LinkedBlockingQueue<>();
            asScript(ALPHA, () -> ui.onEvent(heard::add));

            resetListener().run();

            UiEvent first = heard.poll(AWAIT_SECONDS, TimeUnit.SECONDS);
            UiEvent second = heard.poll(AWAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals(List.of(alpha.handle(), JAGEX_HANDLE), List.of(first.handle(), second.handle()));
            assertEquals(UiEvent.REASON_CONNECTION_RESET, first.reason().orElseThrow());
            assertTrue(first.isDestroyed());
        }

        @Test
        @DisplayName("an old object is dead even when a restarted agent reuses its number")
        void oldHandles_neverReachANewComponent() {
            Canvas old = asScript(ALPHA, GameUiLifetimeTest.this::canvas);
            JagexComponent oldArea = asScript(ALPHA, () -> ui.component(IFACE, COMP));
            resetListener().run();
            nextHandle.set(FIRST_CREATED);

            Canvas fresh = asScript(ALPHA, GameUiLifetimeTest.this::canvas);

            assertNotEquals(old.handle(), fresh.handle(), "the same agent number, a different script handle");
            assertEquals(GameUiBinding.STALE,
                    assertThrows(UiRefusedException.class, () -> asScript(ALPHA, old::destroy)).getMessage());
            assertThrows(UiRefusedException.class, () -> asScript(ALPHA, oldArea::readback));
            verify(rpc, never()).callSync(eq("ui.get"), anyMap());
            assertTrue(asScript(ALPHA, fresh::destroy), "the new component works");
            verify(rpc, times(1)).callSync("ui.destroy", handleParam(FIRST_CREATED));
        }

        @Test
        @DisplayName("after a reset there is nothing left to release")
        void reset_clearsOwnership() {
            asScript(ALPHA, GameUiLifetimeTest.this::canvas);
            asScript(BETA, GameUiLifetimeTest.this::canvas);

            resetListener().run();

            assertEquals(0, api.releaseGameUi(ALPHA) + api.releaseGameUi(BETA));
            verify(rpc, never()).callSync(eq("ui.destroy"), anyMap());
        }
    }

    @Nested
    @DisplayName("N1: subscribing")
    class Subscribing {

        @Test
        @DisplayName("a refused subscribe does not fail a create, and is retried on the next call")
        void refusedSubscribe_isNotFatalForCreate() {
            doThrow(new RpcRemoteException("_debug.subscribe", "subscription to ui.event was refused"))
                    .doNothing()
                    .when(rpc).subscribeTopic(eq("ui.event"), any());

            asScript(ALPHA, GameUiLifetimeTest.this::canvas);
            asScript(ALPHA, GameUiLifetimeTest.this::canvas);

            verify(rpc, times(2)).subscribeTopic(eq("ui.event"), any());
        }

        @Test
        @DisplayName("adding a listener needs the subscription, so its refusal is raised as a UiRefusedException")
        void refusedSubscribe_failsOnEvent() {
            doThrow(new RpcRemoteException("_debug.subscribe", "subscription to ui.event was refused"))
                    .when(rpc).subscribeTopic(eq("ui.event"), any());

            UiRefusedException e = assertThrows(UiRefusedException.class,
                    () -> asScript(ALPHA, () -> ui.onEvent(event -> { })));

            assertEquals("subscription to ui.event was refused", e.getMessage());
            assertNull(gate.current());
        }
    }
}
