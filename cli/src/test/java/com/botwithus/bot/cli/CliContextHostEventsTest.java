package com.botwithus.bot.cli;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.Navigation;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.event.ConnectionLostEvent;
import com.botwithus.bot.api.event.ReconnectStateChangedEvent;
import com.botwithus.bot.api.event.ScriptCrashedEvent;
import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Liveness;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.api.script.ManagementContext;
import com.botwithus.bot.api.script.ManagementScript;
import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.cli.events.HostEvent.ClientClosed;
import com.botwithus.bot.cli.events.HostEvent.ClientOpened;
import com.botwithus.bot.cli.events.HostEvent.CloseCause;
import com.botwithus.bot.cli.events.HostEvent.ConnectionLost;
import com.botwithus.bot.cli.events.HostEvent.ManagementScriptCrashed;
import com.botwithus.bot.cli.events.HostEvent.ReconnectStateChanged;
import com.botwithus.bot.cli.events.HostEvent.ScriptCrashed;
import com.botwithus.bot.cli.events.HostEvent.ScriptLoadFailed;
import com.botwithus.bot.cli.events.HostEvent.ScriptStalled;
import com.botwithus.bot.cli.events.HostEvent.ScriptStarted;
import com.botwithus.bot.cli.events.HostEvent.ScriptStopped;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.core.impl.EventBusImpl;
import com.botwithus.bot.core.runtime.LoadReport;
import com.botwithus.bot.core.runtime.ManagementScriptRunner;
import com.botwithus.bot.core.runtime.ScriptLoadResult;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The host publishes client and script lifecycle onto its own bus, which feeds
 * the connection history. Drives the real {@link CliContext}, bus, history,
 * event bridges and script runners; only the pipe-backed connection is a mock.
 */
class CliContextHostEventsTest {

    private static final Duration FLUSH = Duration.ofSeconds(10);
    private static final long STOP_WAIT_MS = 5_000L;
    private static final long POLL_MS = 10L;
    private static final String PIPE = "BotWithUs_4242";
    private static final ClientRef CLIENT = new ClientRef(PIPE);

    @ScriptManifest(name = "OneShot", version = "1.0", author = "test")
    private static final class OneShot implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return -1; }
        @Override public void onStop() { }
    }

    @ScriptManifest(name = "BrokenManager", version = "1.0", author = "test")
    private static final class BrokenManager implements ManagementScript {
        @Override public void onStart(ManagementContext ctx) {
            throw new IllegalStateException("no orchestrator");
        }
        @Override public int onLoop() { return -1; }
        @Override public void onStop() { }
    }

    @TempDir
    Path tempDir;

    private CliContext ctx;

    @BeforeEach
    void setUp() {
        PrintStream discard = new PrintStream(OutputStream.nullOutputStream());
        LogBuffer logBuffer = new LogBuffer();
        ctx = new CliContext(logBuffer, new LogCapture(logBuffer, discard, discard),
                tempDir.resolve("groups.json"));
    }

    @Test
    void openingAndClosingClientsIsRecordedWithTheCause() {
        ctx.registerConnection(connection("a", null, null));
        ctx.registerConnection(connection("b", null, null));
        ctx.disconnect("a", true);
        ctx.handleConnectionError("b");

        assertEquals(List.of(ClientOpened.class, ClientClosed.class), typesFor("a"));
        assertEquals(CloseCause.DISCONNECTED, closeCause("a"));
        assertEquals(CloseCause.CONNECTION_LOST, closeCause("b"));
    }

    @Test
    void aRejectedDuplicateIsNotReportedAsOpened() {
        ctx.registerConnection(connection("a", null, null));
        ctx.registerConnection(connection("a", null, null));

        assertEquals(List.of(ClientOpened.class), typesFor("a"));
    }

    @Test
    void aLoadFailureWithNoClientConnectedIsStillRecorded() {
        Path jar = Path.of("broken.jar");
        IllegalStateException cause = new IllegalStateException("bad module");

        ctx.recordLoadReport(new LoadReport(List.of(ScriptLoadResult.failure(jar, cause, List.of()))));

        flush();
        assertTrue(ctx.getConnections().isEmpty());
        List<HostEvent> hostWide = ctx.getConnectionHistory().hostWide();
        assertEquals(1, hostWide.size());
        ScriptLoadFailed failed = switch (hostWide.getFirst()) {
            case ScriptLoadFailed f -> f;
            default -> throw new AssertionError("expected a load failure: " + hostWide);
        };
        assertEquals(jar, failed.jar());
        assertEquals(cause, failed.cause());
    }

    @Test
    void aConnectionsGameEventsAreBridged() {
        EventBusImpl connectionBus = new EventBusImpl();
        ctx.registerConnection(connection(PIPE, connectionBus, null));
        LastCrash crash = new LastCrash(Phase.ON_LOOP, 3, Instant.now(), new IllegalStateException("npe"));

        connectionBus.publish(new ConnectionLostEvent(PIPE, new IllegalStateException("eof")));
        connectionBus.publish(new ReconnectStateChangedEvent(PIPE, new ReconnectState.Connected(0L)));
        connectionBus.publish(new ScriptCrashedEvent("Woodcutter", PIPE, crash));

        assertEquals(List.of(ClientOpened.class, ConnectionLost.class,
                ReconnectStateChanged.class, ScriptCrashed.class), typesFor(PIPE));
        List<HostEvent> events = ctx.getConnectionHistory().forClient(PIPE);
        ScriptCrashed crashed = switch (events.getLast()) {
            case ScriptCrashed c -> c;
            default -> throw new AssertionError("expected a crash: " + events);
        };
        assertEquals(new ScriptCrashed(CLIENT, "Woodcutter", crash, crashed.at()), crashed);
    }

    @Test
    void aRealRunnersStartStopAndStallReachTheHistory() {
        ScriptRuntime runtime = runtime();
        ctx.registerConnection(connection(PIPE, null, runtime));

        runtime.startScript(new OneShot());
        ScriptRunner runner = runtime.findRunner("OneShot");
        assertTrue(runner.awaitStop(STOP_WAIT_MS), "script never finished");
        runner.onLivenessChanged(Liveness.STALLED);

        assertEquals(List.of(ClientOpened.class, ScriptStarted.class,
                ScriptStopped.class, ScriptStalled.class), typesFor(PIPE));
    }

    @Test
    void aManagementCrashIsRecordedHostWide() throws InterruptedException {
        ctx.initManagementRuntime();
        ctx.getManagementRuntime().startScript(new BrokenManager());
        ManagementScriptRunner runner = ctx.getManagementRuntime().findRunner("BrokenManager");
        // Not awaitStop(): a run whose onStart threw never releases the stop latch.
        awaitThreadExit(runner);

        flush();
        List<HostEvent> hostWide = ctx.getConnectionHistory().hostWide();
        assertEquals(1, hostWide.size(), "expected one crash: " + hostWide);
        ManagementScriptCrashed crashed = switch (hostWide.getFirst()) {
            case ManagementScriptCrashed c -> c;
            default -> throw new AssertionError("expected a management crash: " + hostWide);
        };
        assertEquals("BrokenManager", crashed.script());
        assertEquals(Phase.ON_START, crashed.crash().phase());
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static void awaitThreadExit(ManagementScriptRunner runner) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_WAIT_MS);
        while (runner.isThreadAlive()) {
            assertTrue(System.nanoTime() < deadline, "management script never finished");
            Thread.sleep(POLL_MS);
        }
    }

    private void flush() {
        assertTrue(ctx.getHostEvents().flush(FLUSH), "host events were not delivered");
    }

    private List<Class<? extends HostEvent>> typesFor(String pipe) {
        flush();
        return ctx.getConnectionHistory().forClient(pipe).stream()
                .<Class<? extends HostEvent>>map(HostEvent::getClass)
                .toList();
    }

    private CloseCause closeCause(String pipe) {
        flush();
        return ctx.getConnectionHistory().forClient(pipe).stream()
                .map(e -> switch (e) {
                    case ClientClosed c -> c.cause();
                    default -> null;
                })
                .filter(c -> c != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no close recorded for " + pipe));
    }

    private static Connection connection(String name, EventBusImpl bus, ScriptRuntime runtime) {
        Connection conn = mock(Connection.class);
        when(conn.getName()).thenReturn(name);
        // The client registry reads these from every registered connection.
        when(conn.getGameStatus()).thenReturn(GameStatus.UNKNOWN);
        when(conn.getEventBus()).thenReturn(bus);
        when(conn.getRuntime()).thenReturn(runtime);
        return conn;
    }

    private static ScriptRuntime runtime() {
        ScriptContext scriptContext = mock(ScriptContext.class);
        when(scriptContext.getNavigation()).thenReturn(mock(Navigation.class));
        ScriptRuntime runtime = new ScriptRuntime(scriptContext);
        runtime.setConnectionName(PIPE);
        return runtime;
    }
}
