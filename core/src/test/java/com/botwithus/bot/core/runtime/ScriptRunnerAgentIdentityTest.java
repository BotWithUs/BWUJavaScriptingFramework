package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.core.runlog.AgentInfoProbe;
import com.botwithus.bot.core.runlog.HostIdentity;
import com.botwithus.bot.core.runlog.KnownNames;
import com.botwithus.bot.core.runlog.RunLogs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A run that opens while {@code rpc.agent_info} is still on its way, which is
 * what "Resume after restart" does: the script starts the moment the client
 * connects. The header waits for the answer, but never past
 * {@link AgentInfoProbe#HEADER_WAIT}.
 */
class ScriptRunnerAgentIdentityTest {

    private static final String SENTINEL = "deadb51d000000000000000000000001";
    private static final String CONNECTION = "conn";
    private static final long ANSWER_DELAY_MS = 200;
    private static final long STOP_WAIT_MS = 10_000;
    /** Scheduling slack on top of the bound; generous so a busy CI box does not flake. */
    private static final long SLACK_MS = 1_000;
    /** How far under the bound a wait may finish: timer granularity, not a lower bound on the wait. */
    private static final long EARLY_MS = 100;
    /** How long start() may take; it must hand off to the script thread, not wait. */
    private static final long START_RETURNS_MS = 300;

    @TempDir
    Path root;

    private RunLogs logs;
    /** When the probe under test was started; set by {@link #runAndReadHeader}. */
    private Instant probeStartedAt;

    @BeforeEach
    void setUp() {
        logs = new RunLogs(root, HostIdentity.current(ScriptRunner.class), Clock.systemUTC());
    }

    @ScriptManifest(name = "Quick Script")
    static final class QuickScript implements BotScript {
        @Override public void onStart(ScriptContext ctx) {}
        @Override public int onLoop() { return -1; }
        @Override public void onStop() {}
        @Override public List<ConfigField> getConfigFields() { return List.of(); }
        @Override public void onConfigUpdate(ScriptConfig config) {}
    }

    @Test
    void anAnswer200msAway_isWaitedFor_andTheHeaderCarriesIt() throws Exception {
        String header = runAndReadHeader(() -> AgentInfoProbe.start(() -> {
            sleep(ANSWER_DELAY_MS);
            return Map.of("build_id", SENTINEL, "game_build", "950-1");
        }, AgentInfoProbe.DEFAULT_DEADLINE));

        assertAll(
                () -> assertTrue(header.contains("\nagent_build: " + SENTINEL + "\n"), header),
                () -> assertTrue(header.contains("\ngame_revision: 950-1\n"), header));
    }

    @Test
    void anAnswerThatNeverComes_isUnknown_andTheRunOpensWithinTheBound() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        try {
            String header = runAndReadHeader(() -> AgentInfoProbe.start(() -> {
                await(never);
                return Map.of("build_id", SENTINEL, "game_build", "950-1");
            }, Duration.ofMinutes(1)));
            Instant startedAt = Instant.parse(field(header, "started_at"));
            long openedAfterMs = Duration.between(probeStartedAt, startedAt).toMillis();
            long boundMs = AgentInfoProbe.HEADER_WAIT.toMillis();
            assertAll(
                    () -> assertTrue(header.contains("\nagent_build: unknown\n"), header),
                    () -> assertTrue(header.contains("\ngame_revision: unknown\n"), header),
                    () -> assertTrue(openedAfterMs <= boundMs + SLACK_MS,
                            "run opened " + openedAfterMs + " ms after start, bound " + boundMs),
                    () -> assertTrue(openedAfterMs >= boundMs - EARLY_MS,
                            "run opened after " + openedAfterMs + " ms: it did not wait at all"));
        } finally {
            never.countDown();
        }
    }

    /**
     * Builds the runner first, and only then starts the probe and the run
     * back to back, as a client that connects and resumes its scripts does.
     * Built the other way round, building the runner (a first Mockito mock
     * costs hundreds of milliseconds) would let a 200 ms answer arrive before
     * the run opened, and the test would pass without the header waiting.
     */
    private String runAndReadHeader(Supplier<AgentInfoProbe.Pending> probe) throws Exception {
        ScriptRunner runner = new ScriptRunner(new QuickScript(), mock(ScriptContext.class),
                n -> { }, () -> { }, e -> { });
        runner.setConnectionName(CONNECTION);
        probeStartedAt = Instant.now();
        AgentInfoProbe.Pending pending = probe.get();
        runner.setRunLogging(() -> new RunLogging(logs, () -> KnownNames.NONE, 1,
                pending.waitingAtMost(AgentInfoProbe.HEADER_WAIT)));
        long beforeStart = System.nanoTime();
        runner.start();
        long startMs = Duration.ofNanos(System.nanoTime() - beforeStart).toMillis();
        assertTrue(startMs < START_RETURNS_MS, "start() blocked its caller for " + startMs + " ms");
        assertTrue(runner.awaitStop(STOP_WAIT_MS), "runner did not stop");
        Path file = logs.currentOrLastLog(CONNECTION, "Quick Script").orElseThrow();
        String text = Files.readString(file);
        return text.substring(0, text.indexOf("\n---\n") + 1);
    }

    private static String field(String header, String key) {
        return header.lines().filter(l -> l.startsWith(key + ": ")).findFirst().orElseThrow()
                .substring(key.length() + 2);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
