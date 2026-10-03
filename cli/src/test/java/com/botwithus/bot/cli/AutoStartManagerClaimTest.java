package com.botwithus.bot.cli;

import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.core.config.ScriptProfileStore;
import com.botwithus.bot.core.pipe.PipeClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The pipe scanner leaves a game alone while another connect to it is under
 * way (the launcher's attach after a launch), and connects to it as usual once
 * that is done. The pipe is real, so "left alone" is read from its server end.
 */
@EnabledOnOs(OS.WINDOWS)
class AutoStartManagerClaimTest {

    /** Not a real Windows pid (those are multiples of 4). */
    private static final long PID = 999_999_993L;
    private static final String NAME = PipeClient.NAME_PREFIX + PID;
    private static final long FASTEST_SCAN_MS = 500L;
    private static final int SCANS_WHILE_CLAIMED = 3;
    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path dir;

    private final AtomicInteger scans = new AtomicInteger();
    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();
    private CliContext ctx;
    private HostSettings settings;
    private AutoStartManager manager;

    @BeforeEach
    void setUp() {
        ctx = TestContexts.inDir(dir, new PrintStream(printed, true));
        settings = HostSettings.open(dir);
        settings.set(SettingKeys.SCAN_INTERVAL_MS, FASTEST_SCAN_MS);
        settings.set(SettingKeys.AUTO_CONNECT, true);
        manager = new AutoStartManager(ctx, new ScriptProfileStore(dir), settings, prefix -> {
            scans.incrementAndGet();
            return List.of(NAME);
        }, Duration.ZERO);
    }

    @AfterEach
    void tearDown() {
        manager.stop();
        settings.close();
    }

    @Test
    void claimedPid_isSkipped_andConnectedOnceReleased() throws Throwable {
        try (TestPipeServer server = new TestPipeServer(NAME)) {
            ctx.pidClaims().tryClaim(PID);
            manager.start();
            await(() -> scans.get() >= SCANS_WHILE_CLAIMED);
            assertEquals(TestPipeServer.ClientState.WAITING, server.clientState(),
                    "the scanner opened a pipe another connect held");
            assertFalse(printed.toString().contains("Already connecting"),
                    "the scanner must skip a claimed pid quietly, not try it every scan: " + printed);
            ctx.pidClaims().release(PID);
            await(() -> stateOf(server) != TestPipeServer.ClientState.WAITING);
            assertNotEquals(TestPipeServer.ClientState.WAITING, server.clientState(),
                    "with the claim released the scanner connects as before");
        }
    }

    private static TestPipeServer.ClientState stateOf(TestPipeServer server) {
        try {
            return server.clientState();
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out");
            }
            Thread.sleep(Duration.ofMillis(50));
        }
    }
}
