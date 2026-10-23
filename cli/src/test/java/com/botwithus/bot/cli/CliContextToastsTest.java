package com.botwithus.bot.cli;

import com.botwithus.bot.api.event.ScriptCrashedEvent;
import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.cli.gui.notify.HostToasts;
import com.botwithus.bot.cli.gui.notify.Notification;
import com.botwithus.bot.cli.gui.notify.Notification.Kind;
import com.botwithus.bot.cli.gui.notify.NotificationOverlay;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.core.impl.EventBusImpl;
import com.botwithus.bot.core.runtime.LoadReport;
import com.botwithus.bot.core.runtime.ScriptLoadResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Toasts wired to the real host the way the app wires them: through the host
 * event bus only. Drives the real {@link CliContext}, its bus, the event bridge
 * from a connection's own bus, and the overlay; only the pipe-backed connection
 * is a mock.
 */
class CliContextToastsTest {

    private static final Duration FLUSH = Duration.ofSeconds(10);
    private static final String PIPE = "BotWithUs_4242";
    private static final String NAME = "Oakheart";

    @TempDir
    Path tempDir;

    private CliContext ctx;
    private HostSettings settings;
    private NotificationOverlay overlay;

    @BeforeEach
    void setUp() {
        PrintStream discard = new PrintStream(OutputStream.nullOutputStream());
        LogBuffer logBuffer = new LogBuffer();
        ctx = new CliContext(logBuffer, new LogCapture(logBuffer, discard, discard),
                tempDir.resolve("groups.json"));
        settings = HostSettings.open(tempDir);
        ctx.setSettings(settings);
        overlay = new NotificationOverlay(Clock.systemUTC());
        HostToasts.attach(ctx, overlay);
    }

    @AfterEach
    void tearDown() {
        settings.close();
    }

    @Test
    void aJarThatFailsToLoad_withNoClientConnected_raisesItsToast() {
        ctx.recordLoadReport(failedJar());

        assertTrue(ctx.getConnections().isEmpty());
        assertEquals(List.of(Kind.LOAD_FAILED), shown());
    }

    @Test
    void aJarThatFailsToLoad_withTwoClientsConnected_raisesOneToast() {
        ctx.registerConnection(connection(PIPE, new EventBusImpl()));
        ctx.registerConnection(connection("BotWithUs_5353", new EventBusImpl()));

        ctx.recordLoadReport(failedJar());

        assertEquals(List.of(Kind.LOAD_FAILED), shown(), "the per-connection broadcast must not toast again");
    }

    @Test
    void aCrashOnAConnectionsOwnBus_raisesOneToast() {
        EventBusImpl connectionBus = new EventBusImpl();
        ctx.registerConnection(connection(PIPE, connectionBus));

        connectionBus.publish(new ScriptCrashedEvent("Woodcutter", PIPE,
                new LastCrash(Phase.ON_LOOP, 1L, Instant.now(), new IllegalStateException("npe"))));

        assertEquals(List.of(Kind.SCRIPT_CRASHED), shown());
        Notification crashed = overlay.active().getFirst();
        assertEquals(ctx.clientKeyOf(PIPE), crashed.client().orElseThrow());
        assertEquals(NAME + " · IllegalStateException in onLoop()", crashed.message(), "named by the client registry");
    }

    private List<Kind> shown() {
        assertTrue(ctx.getHostEvents().flush(FLUSH), "host events were not delivered");
        overlay.update();
        return overlay.active().stream().map(Notification::kind).toList();
    }

    private static LoadReport failedJar() {
        return new LoadReport(List.of(ScriptLoadResult.failure(Path.of("broken.jar"),
                new IllegalStateException("bad module"), List.of())));
    }

    private static Connection connection(String name, EventBusImpl bus) {
        Connection conn = mock(Connection.class);
        when(conn.getName()).thenReturn(name);
        // The client registry reads these from every registered connection.
        when(conn.getGameStatus()).thenReturn(GameStatus.UNKNOWN);
        when(conn.getEventBus()).thenReturn(bus);
        when(conn.getConnectedAt()).thenReturn(Instant.now());
        when(conn.getDisplayName()).thenReturn(Optional.of(NAME));
        return conn;
    }
}
