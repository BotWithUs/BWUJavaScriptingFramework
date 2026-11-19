package com.botwithus.bot.cli;

import com.botwithus.bot.api.gameui.CanvasProps;
import com.botwithus.bot.api.gameui.GameUi;
import com.botwithus.bot.core.impl.GameAPIImpl;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.runtime.ManagementCallerTag;
import com.botwithus.bot.core.runtime.ScriptUiLifecycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The management plugin-UI fan-out {@code CliContext} installs: a management run is
 * opened and released on every connection, including one added mid-run, and a
 * stale run's release reaches none of them.
 */
class ManagementGameUiFanOutTest {

    private static final long JAGEX_HANDLE = 10L;
    private static final long CREATED = 100L;
    private static final int IFACE = 1477;
    private static final int COMP = 61;

    private final ManagementCallerTag tag = new ManagementCallerTag();
    private final List<GameAPIImpl> connections = new CopyOnWriteArrayList<>();
    private final ScriptUiLifecycle lifecycle = new ManagementGameUiFanOut(() -> List.copyOf(connections)).lifecycle();

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

    private RpcClient connect() {
        RpcClient rpc = mock(RpcClient.class);
        when(rpc.callSync(eq("ui.get_component"), anyMap())).thenReturn(handleMap(JAGEX_HANDLE, true));
        when(rpc.callSync(eq("ui.create"), anyMap())).thenReturn(handleMap(CREATED, false));
        when(rpc.callSync(eq("ui.destroy"), anyMap())).thenReturn(Map.of("freed", true));
        GameAPIImpl api = new GameAPIImpl(rpc);
        api.setManagementCallerTag(tag);
        connections.add(api);
        return rpc;
    }

    private void createAs(String owner, GameAPIImpl api) {
        tag.enter(owner);
        try {
            GameUi ui = api.gameUi();
            ui.createCanvas(ui.component(IFACE, COMP), new CanvasProps().size(1, 1));
        } finally {
            tag.exit();
        }
    }

    @Test
    @DisplayName("a run is released on every connection, including one added after it opened")
    void release_reachesEveryConnection() {
        RpcClient early = connect();
        long run = lifecycle.runIds().getAsLong();
        String owner = ManagementCallerTag.ownerOf("Manager", run);
        lifecycle.opened().accept(owner, run);
        RpcClient late = connect();
        createAs(owner, connections.get(0));
        createAs(owner, connections.get(1));

        lifecycle.released().accept(owner, run);

        verify(early).callSync("ui.destroy", Map.of("handle", CREATED));
        verify(late).callSync("ui.destroy", Map.of("handle", CREATED));
    }

    @Test
    @DisplayName("a stale run's release touches no connection's newer run")
    void staleRelease_touchesNothing() {
        RpcClient rpc = connect();
        long oldRun = lifecycle.runIds().getAsLong();
        long newRun = lifecycle.runIds().getAsLong();
        assertNotEquals(oldRun, newRun);
        String oldOwner = ManagementCallerTag.ownerOf("Manager", oldRun);
        String newOwner = ManagementCallerTag.ownerOf("Manager", newRun);
        lifecycle.opened().accept(oldOwner, oldRun);
        lifecycle.opened().accept(newOwner, newRun);
        createAs(newOwner, connections.get(0));

        lifecycle.released().accept(oldOwner, oldRun);

        verify(rpc, never()).callSync(eq("ui.destroy"), anyMap());
    }
}
