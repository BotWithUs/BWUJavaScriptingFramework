package com.botwithus.bot.core.runlog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Logback events reach the run of the thread that logged them: DEBUG included on
 * a script thread even though the root logger is at INFO, nothing from a host
 * thread, and a throwable's full trace after its line.
 */
class RunLogLogbackTest {

    private static final Logger log = LoggerFactory.getLogger("com.example.script.Foo");
    private static final HostIdentity HOST = new HostIdentity("v", 22, "os", "rt");
    private static RunLogs logs;
    private static Level previousRootLevel;
    // ch.qos.logback.classic.Logger fully qualified: name collision with org.slf4j.Logger.
    private static ch.qos.logback.classic.Logger root;

    @TempDir
    static Path dir;

    @BeforeAll
    static void install() {
        // rule-exception: {rule:no-casts} — SLF4J/logback binding boundary, as in
        // LogBufferAppenderTest; the test needs the root logger to pin its level.
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        previousRootLevel = root.getLevel();
        root.setLevel(Level.INFO);
        logs = new RunLogs(dir, HOST, Clock.systemUTC());
        assertTrue(RunLogLogback.install(logs));
    }

    @AfterAll
    static void restore() {
        root.setLevel(previousRootLevel);
    }

    @Test
    void scriptThreadEvents_landInItsRun_withDebug_andFullTraces() throws Exception {
        ScriptRun run = logs.open(new RunLogs.RunRequest(
                new ScriptIdentity("Foo", "1", "a", "local", null), "conn", 1, null,
                () -> new KnownNames(List.of(), List.of("Zezima")), null));
        Thread script = Thread.ofPlatform().name("script-Foo").start(() -> {
            logs.enter(run);
            try {
                log.debug("dbg about Zezima");
                log.trace("trace is below the file's level");
                log.error("it broke", new IllegalStateException("bad state"));
            } finally {
                logs.exit();
            }
        });
        script.join();
        log.debug("host thread debug");
        log.info("host thread info");
        run.close();

        List<String> lines = Files.readAllLines(run.logFile().orElseThrow());
        String text = String.join("\n", lines);
        assertAll(
                () -> assertTrue(lines.stream().anyMatch(l -> l.matches(
                        "\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z DEBUG \\[script-Foo] "
                                + "com\\.example\\.script\\.Foo: dbg about Player#1")), text),
                () -> assertTrue(text.contains(" ERROR [script-Foo] com.example.script.Foo: it broke\n"
                        + "  java.lang.IllegalStateException: bad state\n  at "), text),
                () -> assertFalse(text.contains("trace is below")),
                () -> assertFalse(text.contains("host thread")),
                () -> assertEquals(1, lines.stream().filter(l -> l.contains("dbg about")).count()));
    }
}
