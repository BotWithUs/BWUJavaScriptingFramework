package com.botwithus.bot.cli;

import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.cli.alerts.AlertClassifier;
import com.botwithus.bot.cli.alerts.LiveClientDirectory;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.cli.events.HostEvent.ClientClosed;
import com.botwithus.bot.cli.events.HostEvent.ClientForgotten;
import com.botwithus.bot.cli.events.HostEvent.ClientOpened;
import com.botwithus.bot.cli.events.HostEvent.ReconnectStateChanged;
import com.botwithus.bot.cli.gui.notify.HostToasts;
import com.botwithus.bot.cli.gui.notify.Toast;
import com.botwithus.bot.cli.gui.notify.ToastSink;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.core.alerts.Alert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Forgetting a client that has gone. Drives the real {@link CliContext}, host
 * bus and history; only the pipe-backed connection is a mock.
 */
class CliContextForgetTest {

    private static final Duration FLUSH = Duration.ofSeconds(10);
    private static final String PIPE = "BotWithUs_4242";
    private static final ClientRef CLIENT = new ClientRef(PIPE);

    @TempDir
    Path tempDir;

    private CliContext ctx;
    private final List<HostEvent> published = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        PrintStream discard = new PrintStream(OutputStream.nullOutputStream());
        LogBuffer logBuffer = new LogBuffer();
        ctx = new CliContext(logBuffer, new LogCapture(logBuffer, discard, discard),
                tempDir.resolve("groups.json"));
        ctx.getHostEvents().subscribe(published::add);
    }

    @Test
    void forgettingADeadConnection_removesItDropsItsHistoryAndSaysSo() {
        Connection dead = connection(false);
        ctx.registerConnection(dead);

        assertEquals(ForgetResult.FORGOTTEN, ctx.forget(PIPE));

        flush();
        assertTrue(ctx.getConnections().isEmpty());
        verify(dead).close();
        assertFalse(ctx.getConnectionHistory().clients().contains(CLIENT.key()), "its history must go too");
        assertEquals(List.of(ClientOpened.class, ClientClosed.class, ClientForgotten.class), publishedTypes());
        assertEquals(CLIENT, clientOf(published.getLast()));
    }

    @Test
    void forgettingAClientTheUserStoppedRetrying_raisesNoAlertAndNoToast() {
        List<Alert> alerts = new CopyOnWriteArrayList<>();
        AlertClassifier classifier = new AlertClassifier(new LiveClientDirectory(ctx));
        ctx.getHostEvents().subscribe(event -> classifier.classify(event).ifPresent(alerts::add));
        List<Toast> toasts = new CopyOnWriteArrayList<>();
        try (HostSettings settings = HostSettings.open(tempDir.resolve("settings"))) {
            ctx.setSettings(settings);
            HostToasts.attach(ctx, new RecordingSink(toasts));
            Connection dead = connection(false);
            ctx.registerConnection(dead);
            // What pressing "Stop retrying" publishes once the controller halts.
            ctx.getHostEvents().publish(new ReconnectStateChanged(CLIENT,
                    new ReconnectState.GivingUp(0, 1, new CancellationException("stopped")), Instant.now()));
            flush();

            assertEquals(ForgetResult.FORGOTTEN, ctx.forget(PIPE));

            flush();
            assertEquals(List.of(), alerts, "forgetting is the user's own doing");
            assertEquals(List.of(), toasts);
        }
    }

    @Test
    void aLiveConnectionIsNotForgotten() {
        Connection live = connection(true);
        ctx.registerConnection(live);

        assertEquals(ForgetResult.STILL_CONNECTED, ctx.forget(PIPE));

        flush();
        assertEquals(List.of(live), ctx.getConnections());
        verify(live, never()).close();
        assertEquals(List.of(ClientOpened.class), publishedTypes());
        assertEquals(1, ctx.getConnectionHistory().forClient(PIPE).size());
    }

    @Test
    void aClientAlreadyRemovedCanStillBeForgotten() {
        ctx.registerConnection(connection(false));
        ctx.handleConnectionError(PIPE);
        flush();
        assertTrue(ctx.getConnectionHistory().clients().contains(CLIENT.key()), "history outlives the connection");

        assertEquals(ForgetResult.FORGOTTEN, ctx.forget(PIPE));

        flush();
        assertFalse(ctx.getConnectionHistory().clients().contains(CLIENT.key()));
        assertEquals(List.of(ClientOpened.class, ClientClosed.class, ClientForgotten.class), publishedTypes());
    }

    @Test
    void aPipeTheHostNeverSawIsNotFound() {
        assertEquals(ForgetResult.NOT_FOUND, ctx.forget(PIPE));

        flush();
        assertTrue(published.isEmpty());
    }

    private void flush() {
        assertTrue(ctx.getHostEvents().flush(FLUSH), "host events were not delivered");
    }

    private List<Class<? extends HostEvent>> publishedTypes() {
        return published.stream().<Class<? extends HostEvent>>map(HostEvent::getClass).toList();
    }

    private static ClientRef clientOf(HostEvent event) {
        return switch (event) {
            case HostEvent.ClientEvent client -> client.client();
            default -> throw new AssertionError("not about a client: " + event);
        };
    }

    /** Keeps every toast posted; a withdrawal takes nothing down that the test checks. */
    private record RecordingSink(List<Toast> posted) implements ToastSink {

        @Override
        public void post(Toast toast) {
            posted.add(toast);
        }

        @Override
        public void withdraw(ClientKey client) {
            // Nothing to take down: the test asserts on what was posted.
        }
    }

    private static Connection connection(boolean isAlive) {
        Connection conn = mock(Connection.class);
        when(conn.getName()).thenReturn(PIPE);
        // The client registry reads these from every registered connection.
        when(conn.getGameStatus()).thenReturn(GameStatus.UNKNOWN);
        when(conn.isAlive()).thenReturn(isAlive);
        return conn;
    }
}
