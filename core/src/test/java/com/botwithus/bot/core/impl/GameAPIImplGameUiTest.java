package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.gameui.Canvas;
import com.botwithus.bot.api.gameui.CanvasProps;
import com.botwithus.bot.api.gameui.GameUi;
import com.botwithus.bot.api.gameui.JagexComponent;
import com.botwithus.bot.api.gameui.LayerProps;
import com.botwithus.bot.api.gameui.Text;
import com.botwithus.bot.api.gameui.TextProps;
import com.botwithus.bot.api.gameui.UiEvent;
import com.botwithus.bot.api.gameui.UiHandleInfo;
import com.botwithus.bot.api.gameui.UiLimits;
import com.botwithus.bot.api.gameui.UiRefusedException;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcException;
import com.botwithus.bot.core.rpc.RpcRemoteException;
import com.botwithus.bot.core.runtime.ScriptGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The plugin-UI binding against a mocked {@link RpcClient}, through the same
 * {@code GameAPIImpl} every script gets.
 *
 * <p>The tests that matter most are the ownership ones. Every script on a client
 * shares one agent pipe, and the agent owns handles per pipe, so nothing on the
 * agent side stops one script's stop from taking another's UI with it. What does
 * is that the host destroys by handle, never with {@code ui.destroy_all}, and only
 * the handles the stopping script made. Each such test pairs the "not touched"
 * assertion with a positive one on the other script, so it cannot pass by touching
 * nothing.</p>
 */
class GameAPIImplGameUiTest {

    private static final String ALPHA = "Alpha";
    private static final String BETA = "Beta";
    private static final long JAGEX_HANDLE = 10L;
    private static final long FIRST_CREATED = 100L;
    private static final int GAME_AREA_IFACE = 1477;
    private static final int GAME_AREA_COMP = 61;
    private static final String DESTROY_ALL = "ui.destroy_all";

    private RpcClient rpc;
    private ScriptGate gate;
    private GameAPIImpl api;
    private GameUi ui;
    private final AtomicLong nextHandle = new AtomicLong(FIRST_CREATED);

    @BeforeEach
    void setUp() {
        rpc = mock(RpcClient.class);
        gate = new ScriptGate();
        api = new GameAPIImpl(rpc);
        api.setScriptGate(gate);
        ui = api.gameUi();
        when(rpc.callSync(eq("ui.get_component"), anyMap()))
                .thenReturn(UiCodecTest.handleMap(JAGEX_HANDLE, "layer", true));
        when(rpc.callSync(eq("ui.create"), anyMap()))
                .thenAnswer(inv -> UiCodecTest.handleMap(nextHandle.getAndIncrement(), "canvas", false));
        when(rpc.callSync(eq("ui.destroy"), anyMap())).thenReturn(Map.of("freed", true));
    }

    @AfterEach
    void tearDown() {
        gate.exit();
    }

    private <T> T as(String script, Supplier<T> action) {
        gate.enter(script);
        try {
            return action.get();
        } finally {
            gate.exit();
        }
    }

    private Canvas canvasOf(String script) {
        return as(script, () -> ui.createCanvas(ui.component(GAME_AREA_IFACE, GAME_AREA_COMP),
                new CanvasProps().size(1, 1)));
    }

    private static Map<String, Object> handleParam(long handle) {
        return Map.of("handle", handle);
    }

    private Consumer<Map<String, Object>> capturedPushHandler() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Map<String, Object>>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(rpc).subscribeTopic(eq("ui.event"), captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("verbs")
    class Verbs {

        @Test
        @DisplayName("each call sends its ui.* method with the codec's params")
        void eachVerbSendsItsMethod() {
            Canvas canvas = canvasOf(ALPHA);
            when(rpc.callSync(eq("ui.canvas"), anyMap())).thenReturn(Map.of("num_elements", 2L));
            when(rpc.callSync(eq("ui.get"), anyMap()))
                    .thenReturn(UiCodecTest.handleMap(canvas.handle(), "canvas", false));

            int elements = as(ALPHA, () -> canvas.draw().rect(0, 0, 1, 1, 0).undo().submit());
            as(ALPHA, () -> {
                canvas.toFront();
                canvas.set(new CanvasProps().clickthrough(true));
                return canvas.readback();
            });

            assertEquals(2, elements);
            InOrder order = inOrder(rpc);
            order.verify(rpc).subscribeTopic(eq("ui.event"), any());
            order.verify(rpc).callSync(eq("ui.get_component"),
                    eq(Map.of("iface", GAME_AREA_IFACE, "comp", GAME_AREA_COMP)));
            order.verify(rpc).callSync(eq("ui.create"), argThat(p -> p.get("parent").equals(JAGEX_HANDLE)
                    && "canvas".equals(p.get("type"))));
            order.verify(rpc).callSync(eq("ui.canvas"), argThat(p -> List.of(
                    Map.of("op", "rect", "x", 0, "y", 0, "width", 1, "height", 1, "rgba", 0L),
                    Map.of("op", "undo")).equals(p.get("ops"))));
            order.verify(rpc).callSync("ui.order", Map.of("handle", canvas.handle(), "to", "front"));
            order.verify(rpc).callSync("ui.set", Map.of("handle", canvas.handle(),
                    "props", Map.of("clickthrough", true)));
            order.verify(rpc).callSync("ui.get", handleParam(canvas.handle()));
        }

        @Test
        @DisplayName("the topic is subscribed once, before the first handle exists")
        void subscribesOnceBeforeFirstHandle() {
            canvasOf(ALPHA);
            canvasOf(BETA);

            verify(rpc, times(1)).subscribeTopic(eq("ui.event"), any());
        }

        @Test
        @DisplayName("a canvas batch over the per-call cap is split, not refused or truncated")
        void canvasBatch_splitsAtTheCap() {
            Canvas canvas = canvasOf(ALPHA);
            when(rpc.callSync(eq("ui.canvas"), anyMap())).thenReturn(Map.of("num_elements", 1L));

            as(ALPHA, () -> {
                var batch = canvas.draw();
                for (int i = 0; i <= UiLimits.MAX_CANVAS_OPS; i++) {
                    batch.undo();
                }
                return batch.submit();
            });

            verify(rpc).callSync(eq("ui.canvas"), argThat(p -> sizeOfOps(p) == UiLimits.MAX_CANVAS_OPS));
            verify(rpc).callSync(eq("ui.canvas"), argThat(p -> sizeOfOps(p) == 1));
        }

        private static int sizeOfOps(Map<String, Object> params) {
            return MapHelper.getMapList(params, "ops").size();
        }

        @Test
        @DisplayName("a text's content is read with keys [content]")
        void textContent() {
            Text text = as(ALPHA, () -> ui.createText(ui.component(GAME_AREA_IFACE, GAME_AREA_COMP),
                    new TextProps().text("hi")));
            Map<String, Object> reply = UiCodecTest.handleMap(text.handle(), "text", false);
            reply.put("content", "hi");
            when(rpc.callSync(eq("ui.get"), anyMap())).thenReturn(reply);

            assertEquals("hi", as(ALPHA, text::content));
            verify(rpc).callSync("ui.get", Map.of("handle", text.handle(), "keys", List.of("content")));
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("the agent's refusal reaches the script verbatim")
        void agentRefusal_isVerbatim() {
            String agentSays = "ui type not supported yet: button";
            when(rpc.callSync(eq("ui.create"), anyMap())).thenThrow(new RpcRemoteException("ui.create", agentSays));

            UiRefusedException e = assertThrows(UiRefusedException.class, () -> canvasOf(ALPHA));

            assertAll(
                    () -> assertEquals(agentSays, e.getMessage()),
                    () -> assertEquals("ui.create", e.method()));
        }

        @Test
        @DisplayName("a dead pipe is not reported as a refusal")
        void transportFailure_propagatesAsItself() {
            when(rpc.callSync(eq("ui.create"), anyMap())).thenThrow(new RpcException("pipe gone"));

            assertThrows(RpcException.class, () -> canvasOf(ALPHA));
        }

        @Test
        @DisplayName("a script cannot touch another script's handle; the host can")
        void otherScriptsHandle_isRefusedWithoutACall() {
            Canvas alphas = canvasOf(ALPHA);

            UiRefusedException e = assertThrows(UiRefusedException.class,
                    () -> as(BETA, alphas::destroy));

            assertEquals(GameUiBinding.NOT_YOURS, e.getMessage());
            verify(rpc, never()).callSync(eq("ui.destroy"), anyMap());
            assertTrue(alphas.destroy(), "positive control: the host may destroy any handle");
            verify(rpc).callSync("ui.destroy", handleParam(alphas.handle()));
        }
    }

    @Nested
    @DisplayName("per-script lifetime")
    class Lifetime {

        @Test
        @DisplayName("releasing a script destroys exactly its handles, newest first, never destroy_all")
        void release_destroysOnlyThatScriptsHandles() {
            Canvas alphaFirst = canvasOf(ALPHA);
            Canvas beta = canvasOf(BETA);
            Canvas alphaSecond = canvasOf(ALPHA);

            int removed = api.releaseGameUi(ALPHA);

            assertEquals(2, removed);
            InOrder order = inOrder(rpc);
            order.verify(rpc).callSync("ui.destroy", handleParam(alphaSecond.handle()));
            order.verify(rpc).callSync("ui.destroy", handleParam(alphaFirst.handle()));
            verify(rpc, never()).callSync("ui.destroy", handleParam(beta.handle()));
            verify(rpc, never()).callSync(eq(DESTROY_ALL), any());
            assertEquals(1, api.releaseGameUi(BETA), "the other script's handle is still tracked");
            verify(rpc).callSync("ui.destroy", handleParam(beta.handle()));
        }

        @Test
        @DisplayName("releasing twice destroys nothing the second time")
        void release_isIdempotent() {
            canvasOf(ALPHA);
            api.releaseGameUi(ALPHA);

            assertEquals(0, api.releaseGameUi(ALPHA));
            verify(rpc, times(1)).callSync(eq("ui.destroy"), anyMap());
        }

        @Test
        @DisplayName("a refused destroy does not stop the rest of the release")
        void release_carriesOnPastARefusal() {
            Canvas first = canvasOf(ALPHA);
            Canvas second = canvasOf(ALPHA);
            when(rpc.callSync("ui.destroy", handleParam(second.handle())))
                    .thenThrow(new RpcRemoteException("ui.destroy", "ui: unknown handle"));

            assertEquals(1, api.releaseGameUi(ALPHA));
            verify(rpc).callSync("ui.destroy", handleParam(first.handle()));
        }

        @Test
        @DisplayName("destroyAll from a script is its own handles only")
        void destroyAll_isOwnHandlesOnly() {
            Canvas alpha = canvasOf(ALPHA);
            Canvas beta = canvasOf(BETA);

            assertEquals(1, as(ALPHA, ui::destroyAll));

            verify(rpc).callSync("ui.destroy", handleParam(alpha.handle()));
            verify(rpc, never()).callSync("ui.destroy", handleParam(beta.handle()));
            verify(rpc, never()).callSync(eq(DESTROY_ALL), any());
        }

        @Test
        @DisplayName("ui.list is narrowed to the caller's handles")
        void list_isNarrowedToTheCaller() {
            Canvas alpha = canvasOf(ALPHA);
            Canvas beta = canvasOf(BETA);
            when(rpc.callSync(eq("ui.list"), anyMap())).thenReturn(Map.of("handles", List.of(
                    UiCodecTest.handleMap(JAGEX_HANDLE, "layer", true),
                    UiCodecTest.handleMap(alpha.handle(), "canvas", false),
                    UiCodecTest.handleMap(beta.handle(), "canvas", false))));

            List<Long> alphaSees = as(ALPHA, () -> ui.list().stream().map(UiHandleInfo::handle).toList());

            assertEquals(List.of(JAGEX_HANDLE, alpha.handle()), alphaSees);
            assertEquals(3, ui.list().size(), "the host sees the whole connection");
        }
    }

    @Nested
    @DisplayName("events")
    class Events {

        /** How long a negative check waits for a delivery that must not come. */
        private static final long QUIET_MS = 300L;
        private static final long AWAIT_SECONDS = 5L;

        private final BlockingQueue<String> heard = new LinkedBlockingQueue<>();

        private void listenAs(String script) {
            as(script, () -> ui.onEvent(e -> heard.add(script + ":" + e.handle() + ":" + gate.current()
                    + ":" + e.reason().orElse(""))));
        }

        private String next() throws InterruptedException {
            return heard.poll(AWAIT_SECONDS, TimeUnit.SECONDS);
        }

        private void assertNothingMore() throws InterruptedException {
            assertNull(heard.poll(QUIET_MS, TimeUnit.MILLISECONDS), "nothing else may be delivered");
        }

        @Test
        @DisplayName("an event reaches only the owning script, on its own thread, tagged as that script")
        void event_routedToOwnerOnly() throws InterruptedException {
            Canvas alpha = canvasOf(ALPHA);
            Canvas beta = canvasOf(BETA);
            listenAs(ALPHA);
            listenAs(BETA);
            Consumer<Map<String, Object>> push = capturedPushHandler();

            push.accept(Map.of("handle", alpha.handle(), "hook", "destroyed", "reason", "parent_gone"));
            assertEquals(ALPHA + ":" + alpha.handle() + ":" + ALPHA + ":parent_gone", next());
            push.accept(Map.of("handle", beta.handle(), "hook", "destroyed", "reason", "server_events"));
            assertEquals(BETA + ":" + beta.handle() + ":" + BETA + ":server_events", next());
            assertNothingMore();
        }

        @Test
        @DisplayName("a slow listener in one script does not hold up another script's events")
        void slowListener_delaysOnlyItsOwnScript() throws InterruptedException {
            Canvas alpha = canvasOf(ALPHA);
            Canvas beta = canvasOf(BETA);
            CountDownLatch alphaBlocked = new CountDownLatch(1);
            CountDownLatch releaseAlpha = new CountDownLatch(1);
            as(ALPHA, () -> ui.onEvent(e -> {
                alphaBlocked.countDown();
                awaitQuietly(releaseAlpha);
            }));
            listenAs(BETA);
            Consumer<Map<String, Object>> push = capturedPushHandler();

            push.accept(Map.of("handle", alpha.handle(), "hook", "destroyed"));
            assertTrue(alphaBlocked.await(AWAIT_SECONDS, TimeUnit.SECONDS), "Alpha's listener never ran");
            push.accept(Map.of("handle", beta.handle(), "hook", "destroyed"));

            assertEquals(BETA + ":" + beta.handle() + ":" + BETA + ":", next(),
                    "Beta's event must arrive while Alpha's listener is still blocked");
            releaseAlpha.countDown();
        }

        @Test
        @DisplayName("a destroyed handle is no longer tracked, so release does not destroy it again")
        void destroyedEvent_forgetsTheHandle() {
            Canvas alpha = canvasOf(ALPHA);
            Consumer<Map<String, Object>> push = capturedPushHandler();

            push.accept(Map.of("handle", alpha.handle(), "hook", UiEvent.HOOK_DESTROYED));

            assertEquals(0, api.releaseGameUi(ALPHA));
            verify(rpc, never()).callSync(eq("ui.destroy"), anyMap());
        }

        @Test
        @DisplayName("a shared Jagex handle's event reaches every script that looked it up")
        void jagexEvent_reachesEveryLookup() throws InterruptedException {
            canvasOf(ALPHA);
            canvasOf(BETA);
            listenAs(ALPHA);
            listenAs(BETA);

            capturedPushHandler().accept(Map.of("handle", JAGEX_HANDLE, "hook", "destroyed", "reason", "removed"));

            Set<String> owners = new HashSet<>();
            owners.add(String.valueOf(next()).split(":")[0]);
            owners.add(String.valueOf(next()).split(":")[0]);
            assertEquals(Set.of(ALPHA, BETA), owners, "both lookups hear it");
            assertNothingMore();
        }

        @Test
        @DisplayName("a released or revoked script hears nothing more")
        void releasedOrRevoked_hearsNothing() throws InterruptedException {
            Canvas alpha = canvasOf(ALPHA);
            Canvas beta = canvasOf(BETA);
            listenAs(ALPHA);
            listenAs(BETA);
            Consumer<Map<String, Object>> push = capturedPushHandler();
            api.releaseGameUi(ALPHA);
            gate.revoke(BETA);

            push.accept(Map.of("handle", alpha.handle(), "hook", "destroyed"));
            push.accept(Map.of("handle", beta.handle(), "hook", "destroyed"));

            assertNothingMore();
        }

        @Test
        @DisplayName("closing a subscription stops delivery")
        void closedSubscription_stopsDelivery() throws InterruptedException {
            Canvas alpha = canvasOf(ALPHA);
            var subscription = as(ALPHA, () -> ui.onEvent(e -> heard.add("x")));
            subscription.close();

            capturedPushHandler().accept(Map.of("handle", alpha.handle(), "hook", "destroyed"));

            assertNothingMore();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("a gameval that does not resolve is empty, and nothing is sent")
    void unresolvedGameval_isEmpty() {
        Optional<JagexComponent> found = ui.component("NOT_A_REAL_GAMEVAL");

        assertEquals(Optional.empty(), found);
        verify(rpc, never()).callSync(anyString(), anyMap());
        assertSame(ui, api.gameUi());
    }
}
