package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.gameui.CanvasProps;
import com.botwithus.bot.api.gameui.GameUi;
import com.botwithus.bot.core.impl.EventBusImpl;
import com.botwithus.bot.core.impl.GameAPIImpl;
import com.botwithus.bot.core.impl.MessageBusImpl;
import com.botwithus.bot.core.impl.ScriptContextImpl;
import com.botwithus.bot.core.rpc.RpcClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The wiring test for plugin-UI lifetime: the runtime installs the release hook
 * and every way a script ends runs it, for that script only.
 *
 * <p>{@code GameAPIImplGameUiTest} drives the release directly; only this proves
 * a stop, a crash and a revoke reach it. Two scripts share one connection here,
 * as they do in production, so each test also checks that the survivor's
 * component is untouched and that {@code ui.destroy_all} is never sent.</p>
 */
class ScriptGameUiReleaseTest {

    private static final long JAGEX_HANDLE = 10L;
    private static final long FIRST_CREATED = 100L;
    private static final int GAME_AREA_IFACE = 1477;
    private static final int GAME_AREA_COMP = 61;
    private static final int LOOP_DELAY_MS = 10;
    private static final long AWAIT_SECONDS = 5L;
    private static final long VERIFY_MS = 5_000L;
    /** Alpha's first run, Beta's run, and Alpha's second run. */
    private static final int RUNS_THAT_CREATED = 3;
    /** How long to keep watching that the survivor's component is left alone. */
    private static final long QUIET_MS = 300L;

    /** What a test script does in {@code onLoop}. */
    private enum Behaviour { IDLE, CRASH, IGNORE_STOP }

    /** The shared body of the test scripts; each named class wraps one so manifests differ. */
    private static final class UiMaker {
        private final Behaviour behaviour;
        private final CountDownLatch created = new CountDownLatch(1);
        private final AtomicBoolean released = new AtomicBoolean();
        private final AtomicLong handle = new AtomicLong();

        UiMaker(Behaviour behaviour) {
            this.behaviour = behaviour;
        }

        void onStart(ScriptContext context) {
            GameUi ui = context.getGameAPI().gameUi();
            handle.set(ui.createCanvas(ui.component(GAME_AREA_IFACE, GAME_AREA_COMP),
                    new CanvasProps().size(1, 1)).handle());
            created.countDown();
        }

        int onLoop() {
            switch (behaviour) {
                case CRASH -> throw new IllegalStateException("deliberate crash");
                case IGNORE_STOP -> {
                    while (!released.get()) {
                        Thread.onSpinWait();
                    }
                    return -1;
                }
                case IDLE -> {
                    return LOOP_DELAY_MS;
                }
            }
            return -1;
        }

        long awaitHandle() throws InterruptedException {
            assertTrue(created.await(AWAIT_SECONDS, TimeUnit.SECONDS), "onStart never created its canvas");
            return handle.get();
        }
    }

    @ScriptManifest(name = "UI Alpha", version = "1.0", author = "test", description = "makes a canvas")
    private static final class Alpha implements BotScript {
        private final UiMaker maker;

        Alpha(UiMaker maker) {
            this.maker = maker;
        }

        @Override public void onStart(ScriptContext context) {
            maker.onStart(context);
        }

        @Override public int onLoop() {
            return maker.onLoop();
        }

        @Override public void onStop() {}
    }

    @ScriptManifest(name = "UI Beta", version = "1.0", author = "test", description = "makes a canvas")
    private static final class Beta implements BotScript {
        private final UiMaker maker;

        Beta(UiMaker maker) {
            this.maker = maker;
        }

        @Override public void onStart(ScriptContext context) {
            maker.onStart(context);
        }

        @Override public int onLoop() {
            return maker.onLoop();
        }

        @Override public void onStop() {}
    }

    private RpcClient rpc;
    private ScriptRuntime runtime;
    private UiMaker alpha;
    private final UiMaker beta = new UiMaker(Behaviour.IDLE);

    @BeforeEach
    void setUp() {
        rpc = mock(RpcClient.class);
        AtomicLong nextHandle = new AtomicLong(FIRST_CREATED);
        when(rpc.callSync(eq("ui.get_component"), anyMap())).thenReturn(handleMap(JAGEX_HANDLE, true));
        when(rpc.callSync(eq("ui.create"), anyMap()))
                .thenAnswer(inv -> handleMap(nextHandle.getAndIncrement(), false));
        when(rpc.callSync(eq("ui.destroy"), anyMap())).thenReturn(Map.of("freed", true));
        GameAPIImpl gameAPI = new GameAPIImpl(rpc);
        ScriptGate gate = new ScriptGate();
        runtime = new ScriptRuntime(new ScriptContextImpl(gameAPI, new EventBusImpl(), new MessageBusImpl()));
        runtime.setScriptGate(gate);
        gameAPI.setScriptGate(gate);
        runtime.setGameUiLifecycle(gameAPI.gameUiLifecycle());
    }

    @AfterEach
    void tearDown() {
        if (alpha != null) {
            alpha.released.set(true);
        }
        runtime.stopAll();
    }

    private static Map<String, Object> handleMap(long handle, boolean isJagex) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("handle", handle);
        m.put("type_name", isJagex ? "layer" : "canvas");
        m.put("iface", (long) GAME_AREA_IFACE);
        m.put("comp", (long) GAME_AREA_COMP);
        m.put("key", -1L);
        m.put("parent", isJagex ? 0L : JAGEX_HANDLE);
        m.put("jagex", isJagex);
        m.put("quarantined", false);
        return m;
    }

    /** Starts both scripts and returns {@code {alphaHandle, betaHandle}}. */
    private long[] startBoth(Behaviour alphaBehaviour) throws InterruptedException {
        alpha = new UiMaker(alphaBehaviour);
        runtime.startScript(new Alpha(alpha));
        runtime.startScript(new Beta(beta));
        return new long[] {alpha.awaitHandle(), beta.awaitHandle()};
    }

    private void assertOnlyAlphaDestroyed(long[] handles) {
        verify(rpc, timeout(VERIFY_MS)).callSync("ui.destroy", Map.of("handle", handles[0]));
        verify(rpc, after(QUIET_MS).never()).callSync("ui.destroy", Map.of("handle", handles[1]));
        verify(rpc, never()).callSync(eq("ui.destroy_all"), any());
    }

    @Test
    @DisplayName("stopping a script destroys its components and leaves the other script's")
    void stop_destroysThatScriptsUi() throws Exception {
        long[] handles = startBoth(Behaviour.IDLE);
        ScriptRunner runner = runtime.findRunner("UI Alpha");

        runner.stop();

        assertTrue(runner.awaitStop(VERIFY_MS));
        assertOnlyAlphaDestroyed(handles);
    }

    @Test
    @DisplayName("a script started again after a stop can create again; its new component is kept")
    void restart_reopensTheOwner() throws Exception {
        long[] handles = startBoth(Behaviour.IDLE);
        ScriptRunner runner = runtime.findRunner("UI Alpha");
        runner.stop();
        assertTrue(runner.awaitStop(VERIFY_MS));
        long restartedHandle = Math.max(handles[0], handles[1]) + 1;

        runner.start();

        verify(rpc, timeout(VERIFY_MS).times(RUNS_THAT_CREATED)).callSync(eq("ui.create"), anyMap());
        verify(rpc, after(QUIET_MS).never()).callSync("ui.destroy", Map.of("handle", restartedHandle));
    }

    @Test
    @DisplayName("a script that crashes has its components destroyed")
    void crash_destroysThatScriptsUi() throws Exception {
        assertOnlyAlphaDestroyed(startBoth(Behaviour.CRASH));
    }

    @Test
    @DisplayName("a revoked script, which never reaches its own cleanup, has its components destroyed")
    void revoke_destroysThatScriptsUi() throws Exception {
        long[] handles = startBoth(Behaviour.IGNORE_STOP);

        runtime.findRunner("UI Alpha").revokeAccess();

        assertOnlyAlphaDestroyed(handles);
    }
}
