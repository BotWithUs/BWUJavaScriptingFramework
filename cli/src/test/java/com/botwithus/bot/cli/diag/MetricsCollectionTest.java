package com.botwithus.bot.cli.diag;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.cli.events.HostEventBus;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcMetrics;
import com.botwithus.bot.core.runtime.ScriptProfiler;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Diagnostics switches stop the aggregate bookkeeping on every client, and
 * leave the pulse lane alone.
 */
class MetricsCollectionTest {

    private static final long LOOP_NANOS = 2_000_000L;
    private static final String METHOD = "get_varp";
    private static final Duration DELIVERY = Duration.ofSeconds(5);

    @ScriptManifest(name = "Probe")
    static final class Probe implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return 0; }
        @Override public void onStop() { }
    }

    @TempDir
    Path home;

    private final HostEventBus bus = new HostEventBus();

    @AfterEach
    void tearDown() {
        bus.close();
    }

    @Test
    void switchesOff_stopTheAggregates_butNotTheLane_andBackOnResumes() {
        HostSettings settings = HostSettings.open(home);
        settings.set(SettingKeys.COLLECT_RPC_TIMING, false);
        settings.set(SettingKeys.COLLECT_LOOP_TIMING, false);
        Client client = new Client("BotWithUs_1");
        new MetricsCollection(settings).attach(client.conn());

        client.callAndLoop();
        assertAll(
                () -> assertTrue(client.metrics().snapshot().isEmpty()),
                () -> assertEquals(0, client.profiler().getLoopCount()),
                () -> assertEquals(1, client.profiler().recentLoopNanos().length, "the lane keeps its data"));

        settings.set(SettingKeys.COLLECT_RPC_TIMING, true);
        settings.set(SettingKeys.COLLECT_LOOP_TIMING, true);
        client.callAndLoop();
        assertAll(
                () -> assertEquals(1, client.metrics().snapshot().get(METHOD).callCount()),
                () -> assertEquals(1, client.profiler().getLoopCount()));
    }

    @Test
    void bind_coversClientsAlreadyConnected_andClientsOpenedLater() {
        HostSettings settings = HostSettings.open(home);
        settings.set(SettingKeys.COLLECT_LOOP_TIMING, false);
        List<Connection> connections = new ArrayList<>();
        CliContext ctx = mock(CliContext.class);
        when(ctx.getConnections()).thenReturn(connections);
        when(ctx.getHostEvents()).thenReturn(bus);
        Client before = new Client("BotWithUs_1");
        connections.add(before.conn());

        new MetricsCollection(settings).bind(ctx);
        Client later = new Client("BotWithUs_2");
        connections.add(later.conn());
        bus.publish(new HostEvent.ClientOpened(new ClientRef("BotWithUs_2"), Instant.now()));
        assertTrue(bus.flush(DELIVERY));

        before.callAndLoop();
        later.callAndLoop();
        assertEquals(0, before.profiler().getLoopCount());
        assertEquals(0, later.profiler().getLoopCount());
    }

    /** A client with real metrics and a real runtime holding one registered script. */
    private static final class Client {
        private final RpcMetrics metrics = new RpcMetrics();
        private final ScriptRuntime runtime = new ScriptRuntime(mock(ScriptContext.class));
        private final Connection conn = mock(Connection.class);
        private final ScriptProfiler profiler;

        Client(String pipe) {
            RpcClient rpc = mock(RpcClient.class);
            when(rpc.getMetrics()).thenReturn(metrics);
            when(conn.getName()).thenReturn(pipe);
            when(conn.getRpc()).thenReturn(rpc);
            when(conn.getRuntime()).thenReturn(runtime);
            profiler = runtime.registerScript(new Probe()).getProfiler();
        }

        Connection conn() {
            return conn;
        }

        RpcMetrics metrics() {
            return metrics;
        }

        ScriptProfiler profiler() {
            return profiler;
        }

        void callAndLoop() {
            metrics.recordCall(METHOD, LOOP_NANOS, false);
            profiler.recordLoop(LOOP_NANOS);
        }
    }
}
