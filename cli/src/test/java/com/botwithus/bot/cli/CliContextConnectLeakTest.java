package com.botwithus.bot.cli;

import com.botwithus.bot.cli.groups.GroupsFile;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A connect that fails after the agent pipe opened must close the pipe. The
 * agent has four pipe slots; a leaked client holds one until garbage
 * collection, and an attach that retries would leak one per attempt.
 *
 * <p>The pipe here is real: a {@link TestPipeServer} named like an agent pipe
 * for a pid that has no shared memory, so the connect opens the pipe and then
 * fails on the mapping.</p>
 */
@EnabledOnOs(OS.WINDOWS)
class CliContextConnectLeakTest {

    /** Not a real Windows pid (those are multiples of 4), so no mapping exists for it. */
    private static final long NO_SUCH_PID = 999_999_999L;
    private static final Duration CLOSE_WITHIN = Duration.ofSeconds(2);

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
    void connectThatFailsOnTheMapping_closesThePipeItOpened() throws Throwable {
        String name = PipeClient.NAME_PREFIX + NO_SUCH_PID;
        try (TestPipeServer server = new TestPipeServer(name)) {
            ctx.connect(name);
            assertTrue(ctx.getConnections().isEmpty(), "the connect must fail: no mapping exists");
            assertEquals(TestPipeServer.ClientState.CLOSED, awaitClosed(server),
                    "the failed connect left its pipe client open, holding an agent pipe slot");
        }
    }

    private static TestPipeServer.ClientState awaitClosed(TestPipeServer server) throws Throwable {
        long deadline = System.nanoTime() + CLOSE_WITHIN.toNanos();
        TestPipeServer.ClientState state = server.clientState();
        while (state != TestPipeServer.ClientState.CLOSED && System.nanoTime() < deadline) {
            Thread.sleep(Duration.ofMillis(20));
            state = server.clientState();
        }
        return state;
    }
}
