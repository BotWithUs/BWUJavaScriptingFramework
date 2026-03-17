package com.botwithus.bot.core.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalLogManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void wrapStdout_writesToDelegateAndLogFile() throws IOException {
        Path baseDir = tempDir.resolve(".botwithus");

        try (LocalLogManager manager = LocalLogManager.create(baseDir, "test-session")) {
            ByteArrayOutputStream delegateBytes = new ByteArrayOutputStream();
            PrintStream delegate = new PrintStream(delegateBytes, true, StandardCharsets.UTF_8);
            PrintStream wrapped = manager.wrapStdout(delegate);

            wrapped.println("hello stdout");
            wrapped.flush();

            assertTrue(Files.isDirectory(baseDir.resolve("logs")));
            assertTrue(Files.exists(manager.getStdoutFile()));
            assertEquals("hello stdout" + System.lineSeparator(), delegateBytes.toString(StandardCharsets.UTF_8));
            assertEquals("hello stdout" + System.lineSeparator(),
                    Files.readString(manager.getStdoutFile(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void wrapStderr_writesToDelegateAndLogFile() throws IOException {
        Path baseDir = tempDir.resolve(".botwithus");

        try (LocalLogManager manager = LocalLogManager.create(baseDir, "test-session")) {
            ByteArrayOutputStream delegateBytes = new ByteArrayOutputStream();
            PrintStream delegate = new PrintStream(delegateBytes, true, StandardCharsets.UTF_8);
            PrintStream wrapped = manager.wrapStderr(delegate);

            wrapped.println("hello stderr");
            wrapped.flush();

            assertTrue(Files.exists(manager.getStderrFile()));
            assertEquals("hello stderr" + System.lineSeparator(), delegateBytes.toString(StandardCharsets.UTF_8));
            assertEquals("hello stderr" + System.lineSeparator(),
                    Files.readString(manager.getStderrFile(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void logUncaughtException_writesStackTraceToStderrLog() throws IOException {
        Path baseDir = tempDir.resolve(".botwithus");

        try (LocalLogManager manager = LocalLogManager.create(baseDir, "test-session")) {
            ByteArrayOutputStream delegateBytes = new ByteArrayOutputStream();
            PrintStream delegate = new PrintStream(delegateBytes, true, StandardCharsets.UTF_8);
            PrintStream wrapped = manager.wrapStderr(delegate);
            PrintStream originalErr = System.err;
            try {
                System.setErr(wrapped);
                IllegalStateException crash = new IllegalStateException("boom");
                manager.logUncaughtException(Thread.currentThread(), crash);
            } finally {
                System.setErr(originalErr);
            }

            String log = Files.readString(manager.getStderrFile(), StandardCharsets.UTF_8);
            assertTrue(log.contains("[CRASH] Uncaught exception in thread"));
            assertTrue(log.contains("IllegalStateException: boom"));
            assertTrue(log.contains("logUncaughtException_writesStackTraceToStderrLog"));
        }
    }

    @Test
    void rotatesAndCapsFilesPerStream() throws IOException {
        Path baseDir = tempDir.resolve(".botwithus");

        try (LocalLogManager manager = LocalLogManager.create(baseDir, "test-session", 32, 3, 10, null)) {
            ByteArrayOutputStream delegateBytes = new ByteArrayOutputStream();
            PrintStream delegate = new PrintStream(delegateBytes, true, StandardCharsets.UTF_8);
            PrintStream wrapped = manager.wrapStdout(delegate);

            for (int i = 0; i < 5; i++) {
                wrapped.println("line-" + i + "-abcdefghijklmnop");
            }
            wrapped.flush();

            List<Path> stdoutLogs = Files.list(manager.getLogDir())
                    .filter(path -> path.getFileName().toString().contains(".out"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();

            assertEquals(3, stdoutLogs.size());
            assertTrue(stdoutLogs.stream().allMatch(path -> size(path) <= 32));
        }
    }

    @Test
    void prunesOldLogsAcrossSessions() throws IOException {
        Path baseDir = tempDir.resolve(".botwithus");
        Path logDir = baseDir.resolve("logs");
        Files.createDirectories(logDir);

        for (int i = 0; i < 5; i++) {
            Path path = logDir.resolve("old-" + i + ".log");
            Files.writeString(path, "old-" + i, StandardCharsets.UTF_8);
            Files.setLastModifiedTime(path, FileTime.fromMillis(1_000L + i));
        }

        try (LocalLogManager ignored = LocalLogManager.create(baseDir, "test-session", 1024, 2, 4, null)) {
            List<Path> allLogs = Files.list(logDir)
                    .filter(path -> path.getFileName().toString().endsWith(".log"))
                    .toList();

            assertEquals(4, allLogs.size());
            assertTrue(allLogs.stream().noneMatch(path -> path.getFileName().toString().equals("old-0.log")));
            assertTrue(allLogs.stream().noneMatch(path -> path.getFileName().toString().equals("old-1.log")));
            assertTrue(allLogs.stream().anyMatch(path -> path.getFileName().toString().contains("test-session")));
        }
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
