package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.gameui.Canvas;
import com.botwithus.bot.api.gameui.CanvasProps;
import com.botwithus.bot.api.gameui.GameUi;
import com.botwithus.bot.api.gameui.JagexComponent;
import com.botwithus.bot.api.gameui.UiRefusedException;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcRemoteException;
import com.botwithus.bot.core.runtime.ManagementCallerTag;
import com.botwithus.bot.core.runtime.ScriptGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * The second review round's binding-level holes, each pinned by a test that fails
 * without its fix: listeners must run as exactly their owner whatever thread
 * delivers them (R1); a release names one run, so a quarantined run's late cleanup
 * cannot touch its replacement (R2); a lost subscription is retried (N-a); a
 * refused destroy of a shared Jagex handle does not untrack it (N-b); a revoked
 * management run cannot create anywhere (N-d).
 */
class GameUiRunsAndTagsTest {

    private static final String ALPHA = "Alpha";
    private static final String MANAGER = ManagementCallerTag.ownerOf("Manager", 1L);
    private static final long JAGEX_HANDLE = 10L;
    private static final long FIRST_CREATED = 100L;
    private static final int IFACE = 1477;
    private static final int COMP = 61;
    private static final long AWAIT_SECONDS = 5L;
    private static final long FIRST_RUN = 1L;
    private static final long SECOND_RUN = 2L;
    private static final String NONE = "null";

    private RpcClient rpc;
    private ScriptGate gate;
    private ManagementCallerTag managers;
    private GameAPIImpl api;
    private GameUi ui;
    private final AtomicLong nextHandle = new AtomicLong(FIRST_CREATED);

    @BeforeEach
    void setUp() {
        rpc = mock(RpcClient.class);
        gate = new ScriptGate();
        managers = new ManagementCallerTag();
        api = new GameAPIImpl(rpc);
        api.setScriptGate(gate);
        api.setManagementCallerTag(managers);
        ui = api.gameUi();
        when(rpc.callSync(eq("ui.get_component"), anyMap())).thenReturn(UiCodecTest.handleMap(JAGEX_HANDLE, "layer", true));
        when(rpc.callSync(eq("ui.create"), anyMap()))
                .thenAnswer(inv -> UiCodecTest.handleMap(nextHandle.getAndIncrement(), "canvas", false));
        when(rpc.callSync(eq("ui.destroy"), anyMap())).thenReturn(Map.of("freed", true));
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

    private Consumer<Map<String, Object>> pushHandler() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Map<String, Object>>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(rpc).subscribeTopic(eq("ui.event"), captor.capture(), any());
        return captor.getValue();
    }

    /** Delivers a push from a fresh thread that carries {@code scriptTag} and {@code managerTag}. */
    private void pushFromTaggedThread(Map<String, Object> data, String scriptTag, String managerTag)
            throws InterruptedException {
        Consumer<Map<String, Object>> handler = pushHandler();
        Thread t = Thread.ofPlatform().start(() -> {
            if (scriptTag != null) {
                gate.enter(scriptTag);
            }
            if (managerTag != null) {
                managers.enter(managerTag);
            }
            handler.accept(data);
        });
        t.join();
    }

    private String tags() {
        return gate.current() + "/" + managers.current();
    }

    @Test
    @DisplayName("R1: a management listener runs as the management run only, though its lane was born on a script thread")
    void managementListener_isNotTheScriptThatSpawnedItsLane() throws InterruptedException {
        BlockingQueue<String> seen = new LinkedBlockingQueue<>();
        Canvas mine = asManager(this::canvas);
        asManager(() -> ui.onEvent(e -> seen.add(tags())));

        pushFromTaggedThread(Map.of("handle", mine.handle(), "hook", "destroyed"), ALPHA, null);

        assertEquals(NONE + "/" + MANAGER, seen.poll(AWAIT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("R1: a script listener runs as that script only, though the delivering thread carried a management tag")
    void scriptListener_dropsAStaleManagementTag() throws InterruptedException {
        BlockingQueue<String> seen = new LinkedBlockingQueue<>();
        Canvas alphas = asScript(ALPHA, this::canvas);
        asScript(ALPHA, () -> ui.onEvent(e -> seen.add(tags())));

        pushFromTaggedThread(Map.of("handle", alphas.handle(), "hook", "destroyed"), null, MANAGER);

        assertEquals(ALPHA + "/" + NONE, seen.poll(AWAIT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("R1: a host listener runs untagged, and can act on its own component from the callback")
    void hostListener_runsUntaggedAndCanCallBack() throws InterruptedException {
        BlockingQueue<String> seen = new LinkedBlockingQueue<>();
        Canvas hosts = canvas();
        Canvas other = canvas();
        ui.onEvent(e -> {
            seen.add(tags());
            seen.add(String.valueOf(other.destroy()));
        });

        pushFromTaggedThread(Map.of("handle", hosts.handle(), "hook", "destroyed"), ALPHA, MANAGER);

        assertEquals(NONE + "/" + NONE, seen.poll(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals("true", seen.poll(AWAIT_SECONDS, TimeUnit.SECONDS), "not refused as NOT_YOURS");
    }

    @Test
    @DisplayName("R2: releasing an old run after a newer one opened destroys nothing and closes nothing")
    void staleRunRelease_isANoOp() {
        api.openGameUi(ALPHA, FIRST_RUN);
        api.openGameUi(ALPHA, SECOND_RUN);
        Canvas fresh = asScript(ALPHA, this::canvas);

        assertEquals(0, api.releaseGameUi(ALPHA, FIRST_RUN));

        verify(rpc, never()).callSync(eq("ui.destroy"), anyMap());
        asScript(ALPHA, this::canvas);
        assertEquals(2, api.releaseGameUi(ALPHA, SECOND_RUN), "the current run's release still works");
        verify(rpc).callSync("ui.destroy", Map.of("handle", fresh.handle()));
    }

    @Test
    @DisplayName("N-a: a subscription lost on reconnect is sent again on the next call")
    void lostSubscription_isRetried() {
        asScript(ALPHA, this::canvas);
        ArgumentCaptor<Runnable> onLost = ArgumentCaptor.forClass(Runnable.class);
        verify(rpc).subscribeTopic(eq("ui.event"), any(), onLost.capture());

        onLost.getValue().run();
        asScript(ALPHA, this::canvas);

        verify(rpc, times(2)).subscribeTopic(eq("ui.event"), any(), any());
    }

    @Test
    @DisplayName("N-b: a refused destroy of a shared Jagex handle leaves it tracked for its other owners")
    void refusedJagexDestroy_keepsTheHandle() throws InterruptedException {
        BlockingQueue<String> seen = new LinkedBlockingQueue<>();
        JagexComponent area = asScript(ALPHA, () -> ui.component(IFACE, COMP));
        asScript(ALPHA, () -> ui.onEvent(e -> seen.add("heard " + e.handle())));
        doThrow(new RpcRemoteException("ui.destroy",
                "ui: handle is a Jagex component: read-only, usable only as a parent or with ui.get"))
                .when(rpc).callSync("ui.destroy", Map.of("handle", JAGEX_HANDLE));

        assertThrows(UiRefusedException.class, () -> api.uiDestroy(area.handle()));
        pushHandler().accept(Map.of("handle", JAGEX_HANDLE, "hook", "destroyed"));

        assertEquals("heard " + JAGEX_HANDLE, seen.poll(AWAIT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("N-d: a revoked management run cannot create, on any connection")
    void revokedManagementRun_cannotCreate() {
        managers.revoke(MANAGER);

        UiRefusedException e = assertThrows(UiRefusedException.class, () -> asManager(this::canvas));

        assertEquals(GameUiBinding.RELEASED, e.getMessage());
        verify(rpc).callSync("ui.destroy", Map.of("handle", FIRST_CREATED));
        assertTrue(asScript(ALPHA, this::canvas).handle() > 0, "positive control: a script still creates");
    }
}
