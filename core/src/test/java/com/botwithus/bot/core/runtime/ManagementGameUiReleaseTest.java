package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.gameui.CanvasProps;
import com.botwithus.bot.api.gameui.GameUi;
import com.botwithus.bot.api.script.ManagementContext;
import com.botwithus.bot.api.script.ManagementScript;
import com.botwithus.bot.core.impl.GameAPIImpl;
import com.botwithus.bot.core.rpc.RpcClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The wiring test for management-script plugin-UI ownership: the management runtime
 * tags the script's thread, so what it creates is its own rather than the host's,
 * and its stop releases it.
 *
 * <p>Before this, a management thread was untagged and so counted as the host: its
 * components were never released, and it could list and destroy every script's.
 * {@code GameUiLifetimeTest} covers the isolation at the binding; this proves the
 * runtime installs the tag and the release.</p>
 */
class ManagementGameUiReleaseTest {

    private static final String NAME = "UI Manager";
    private static final long JAGEX_HANDLE = 10L;
    private static final long CREATED = 100L;
    private static final int IFACE = 1477;
    private static final int COMP = 61;
    private static final int LOOP_DELAY_MS = 10;
    private static final long AWAIT_SECONDS = 5L;
    private static final long VERIFY_MS = 5_000L;

    @ScriptManifest(name = NAME, version = "1.0", author = "test", description = "makes a canvas")
    private static final class Manager implements ManagementScript {
        private final GameAPIImpl api;
        private final CompletableFuture<String> ownerSeen = new CompletableFuture<>();
        private final ManagementCallerTag tag;

        Manager(GameAPIImpl api, ManagementCallerTag tag) {
            this.api = api;
            this.tag = tag;
        }

        @Override public void onStart(ManagementContext ctx) {
            GameUi ui = api.gameUi();
            ui.createCanvas(ui.component(IFACE, COMP), new CanvasProps().size(1, 1));
            ownerSeen.complete(tag.current());
        }

        @Override public int onLoop() {
            return LOOP_DELAY_MS;
        }

        @Override public void onStop() {}
    }

    private static Map<String, Object> handleMap(long handle, boolean isJagex) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("handle", handle);
        m.put("type_name", isJagex ? "layer" : "canvas");
        m.put("iface", (long) IFACE);
        m.put("comp", (long) COMP);
        m.put("key", -1L);
        m.put("parent", isJagex ? 0L : JAGEX_HANDLE);
        m.put("jagex", isJagex);
        m.put("quarantined", false);
        return m;
    }

    @Test
    @DisplayName("a management script's components are its own and are destroyed when it stops")
    void managementStop_releasesItsComponents() throws Exception {
        RpcClient rpc = mock(RpcClient.class);
        when(rpc.callSync(eq("ui.get_component"), anyMap())).thenReturn(handleMap(JAGEX_HANDLE, true));
        when(rpc.callSync(eq("ui.create"), anyMap())).thenReturn(handleMap(CREATED, false));
        when(rpc.callSync(eq("ui.destroy"), anyMap())).thenReturn(Map.of("freed", true));
        GameAPIImpl api = new GameAPIImpl(rpc);
        ManagementCallerTag tag = new ManagementCallerTag();
        api.setManagementCallerTag(tag);
        ManagementScriptRuntime runtime = new ManagementScriptRuntime(mock(ManagementContext.class));
        runtime.setCallerTag(tag);
        runtime.setGameUiLifecycle(api.gameUiLifecycle());
        Manager manager = new Manager(api, tag);

        ManagementScriptRunner runner = runtime.registerScript(manager);
        runner.start();
        String seen = manager.ownerSeen.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(runner.uiOwner(), seen, "the management thread must carry its own owner tag");
        assertTrue(runner.uiOwner().startsWith("<mgmt:" + NAME + "#"), "one owner per run: " + runner.uiOwner());
        runner.stop();

        assertTrue(runner.awaitStop(VERIFY_MS));
        verify(rpc, timeout(VERIFY_MS)).callSync("ui.destroy", Map.of("handle", CREATED));
    }
}
