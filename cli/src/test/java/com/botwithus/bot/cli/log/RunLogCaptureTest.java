package com.botwithus.bot.cli.log;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import com.botwithus.bot.core.runlog.HostIdentity;
import com.botwithus.bot.core.runlog.KnownNames;
import com.botwithus.bot.core.runlog.RunLogLogback;
import com.botwithus.bot.core.runlog.RunLogs;
import com.botwithus.bot.core.runlog.ScriptIdentity;
import com.botwithus.bot.core.runlog.ScriptRun;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host's real logging setup ({@code logback.xml} plus the stdout/stderr tee)
 * with run logs installed: a script thread's DEBUG reaches its run log and
 * nowhere else, and a line it logs or prints is in the run log exactly once,
 * even though the console appender writes through the same tee.
 */
class RunLogCaptureTest {

    private static final Logger log = LoggerFactory.getLogger("com.example.script.Foo");
    private static final ByteArrayOutputStream CONSOLE = new ByteArrayOutputStream();
    private static final LogBuffer BUFFER = new LogBuffer(1000);
    private static LogCapture capture;
    private static RunLogs logs;

    @TempDir
    static Path dir;

    @BeforeAll
    static void install() {
        // rule-exception: {rule:no-casts} {rule:no-instanceof} — SLF4J/logback binding
        // boundary, the same seam ImGuiApp.wireLogBufferAppender crosses.
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Appender<ILoggingEvent> appender =
                context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("LOG_BUFFER");
        if (appender instanceof LogBufferAppender buffer) {
            buffer.setLogBuffer(BUFFER);
        }
        PrintStream console = new PrintStream(CONSOLE, true, StandardCharsets.UTF_8);
        capture = new LogCapture(BUFFER, console, console);
        capture.install();
        logs = new RunLogs(dir, new HostIdentity("v", 22, "os", "rt"), Clock.systemUTC());
        assertTrue(RunLogLogback.install(logs));
        capture.setRunLogs(logs);
    }

    @AfterAll
    static void restore() {
        capture.restore();
    }

    @Test
    void scriptThreadOutput_isInTheRunLogOnce_andDebugStaysOutOfConsoleAndBuffer() throws Exception {
        ScriptRun run = logs.open(new RunLogs.RunRequest(
                new ScriptIdentity("Foo", "1", "a", "local", null), "Main Acc", 1, null,
                () -> new KnownNames(List.of("Main Acc"), List.of()), null));
        Thread script = Thread.ofPlatform().name("script-Foo").start(() -> {
            logs.enter(run);
            MDC.put("script.name", "Foo");
            try {
                log.debug("debug-line-7f3");
                log.info("info-line-7f3 on Main Acc");
                System.out.println("printed-line-7f3");
                System.err.println("err-line-7f3");
            } finally {
                MDC.clear();
                logs.exit();
            }
        });
        script.join();
        run.close();

        String file = Files.readString(run.logFile().orElseThrow(), StandardCharsets.UTF_8);
        String console = CONSOLE.toString(StandardCharsets.UTF_8);
        List<String> bufferLevels = BUFFER.tail(1000).stream()
                .filter(e -> e.message().contains("7f3")).map(LogEntry::level).toList();
        assertAll(
                () -> assertEquals(1, count(file, "debug-line-7f3"), file),
                () -> assertEquals(1, count(file, "info-line-7f3"), file),
                () -> assertTrue(file.contains("info-line-7f3 on Account#1"), file),
                () -> assertEquals(1, count(file, "printed-line-7f3"), file),
                () -> assertTrue(file.contains(" INFO  [script-Foo] stdout: printed-line-7f3"), file),
                () -> assertTrue(file.contains(" ERROR [script-Foo] stderr: err-line-7f3"), file),
                () -> assertFalse(console.contains("debug-line-7f3"), "DEBUG must not reach the console"),
                () -> assertTrue(console.contains("info-line-7f3"), "INFO still reaches the console"),
                () -> assertFalse(bufferLevels.contains("DEBUG"), "DEBUG must not reach the GUI buffer"),
                () -> assertTrue(bufferLevels.contains("INFO")));
    }

    private static long count(String haystack, String needle) {
        return haystack.lines().filter(l -> l.contains(needle)).count();
    }
}
