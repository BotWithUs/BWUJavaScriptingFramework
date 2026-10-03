package com.botwithus.bot.cli;

import com.botwithus.bot.cli.groups.GroupsFile;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.core.launcher.AttachResult;
import com.botwithus.bot.core.pipe.PipeClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The launcher's attach after a launch, and the pid claim it shares with the
 * other connect paths (launcher ADR 0007, section 10.1). The pipes are real
 * ({@link TestPipeServer}), so "no second {@code PipeClient} was opened" is read
 * from the server end of the pipe rather than assumed.
 */
@EnabledOnOs(OS.WINDOWS)
class CliContextAttachTest {

    /** Not a real Windows pid (those are multiples of 4): no agent, no mapping. */
    private static final long PID = 999_999_997L;
    private static final String NAME = PipeClient.NAME_PREFIX + PID;
    private static final Duration SHORT = Duration.ofMillis(600);
    private static final Duration HELD = Duration.ofMillis(300);

    @TempDir
    Path tempDir;

    private CliContext ctx;

    @BeforeEach
    void setUp() {
        PrintStream discard = new PrintStream(OutputStream.nullOutputStream());
        LogBuffer logBuffer = new LogBuffer();
        ctx = new CliContext(logBuffer, new LogCapture(logBuffer, discard, discard),
                tempDir.resolve(GroupsFile.FILE_NAME));
    }

    @Test
    void alreadyConnected_isAttachedWithoutOpeningAPipe() throws Throwable {
        try (TestPipeServer server = new TestPipeServer(NAME)) {
            assertTrue(ctx.registerConnection(connection(NAME)));
            assertEquals(new AttachResult.Attached(NAME), ctx.attachLaunchedClient(PID, SHORT));
            assertEquals(TestPipeServer.ClientState.WAITING, server.clientState(), "no pipe client was opened");
        }
    }

    @Test
    void connectUnderWay_attachWaitsForIt_andUsesItsConnection() throws Throwable {
        try (TestPipeServer server = new TestPipeServer(NAME)) {
            assertTrue(ctx.pidClaims().tryClaim(PID), "the test plays the pipe scanner's connect");
            CompletableFuture<AttachResult> attach = CompletableFuture.supplyAsync(
                    () -> ctx.attachLaunchedClient(PID, Duration.ofSeconds(5)));
            Thread.sleep(HELD);
            assertAll(() -> assertFalse(attach.isDone(), "the attach must wait for the connect under way"),
                    () -> assertEquals(TestPipeServer.ClientState.WAITING, server.clientState(),
                            "the attach opened the pipe while another connect held the pid"));
            ctx.registerConnection(connection(NAME));
            ctx.pidClaims().release(PID);
            assertEquals(new AttachResult.Attached(NAME), attach.get(5, TimeUnit.SECONDS));
            assertEquals(TestPipeServer.ClientState.WAITING, server.clientState(), "no second pipe client");
        }
    }

    @Test
    void attachUnderWay_pipeScannerConnectStaysAway() throws Throwable {
        try (TestPipeServer server = new TestPipeServer(NAME)) {
            assertTrue(ctx.pidClaims().tryClaim(PID), "the test plays the launcher's attach");
            assertTrue(ctx.isConnecting(PID));
            ctx.connect(NAME);
            assertAll(() -> assertTrue(ctx.getConnections().isEmpty()),
                    () -> assertEquals(TestPipeServer.ClientState.WAITING, server.clientState(),
                            "a connect opened the pipe while the attach held the pid"));
            ctx.pidClaims().release(PID);
            assertFalse(ctx.isConnecting(PID));
        }
    }

    @Test
    void pipeUpButNoMapping_failsInsteadOfHanging_andClosesThePipe() throws Throwable {
        try (TestPipeServer server = new TestPipeServer(NAME)) {
            long start = System.nanoTime();
            AttachResult result = ctx.attachLaunchedClient(PID, SHORT);
            Duration took = Duration.ofNanos(System.nanoTime() - start);
            assertAll(() -> assertTrue(result.toString().contains("could not attach to pid " + PID), result::toString),
                    () -> assertEquals(AttachResult.Failed.class, result.getClass()),
                    () -> assertTrue(took.compareTo(SHORT.multipliedBy(4)) < 0, "took " + took),
                    () -> assertEquals(TestPipeServer.ClientState.CLOSED, server.clientState()),
                    () -> assertFalse(ctx.isConnecting(PID), "the claim is released"));
        }
    }

    @Test
    void noPipeAtAll_failsAfterTheTimeout() {
        AttachResult result = ctx.attachLaunchedClient(PID, SHORT);
        assertEquals(AttachResult.Failed.class, result.getClass());
        assertTrue(ctx.getConnections().isEmpty());
    }

    private static Connection connection(String name) {
        Connection conn = mock(Connection.class);
        when(conn.getName()).thenReturn(name);
        when(conn.getGameStatus()).thenReturn(GameStatus.UNKNOWN);
        return conn;
    }
}
