package com.botwithus.bot.cli.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that LogBufferAppender correctly captures Logback events
 * into the LogBuffer with MDC context.
 */
class LogBufferAppenderTest {

    private LogBuffer logBuffer;
    private LogBufferAppender appender;
    private Logger testLogger;

    @BeforeEach
    void setUp() {
        logBuffer = new LogBuffer(100);

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        appender = new LogBufferAppender();
        appender.setContext(context);
        appender.start();
        LogBufferAppender.setLogBuffer(logBuffer);

        testLogger = context.getLogger("com.botwithus.test.TestClass");
        testLogger.addAppender(appender);
        testLogger.setLevel(Level.ALL);
        testLogger.setAdditive(false); // don't propagate to root
    }

    @AfterEach
    void tearDown() {
        testLogger.detachAppender(appender);
        appender.stop();
        LogBufferAppender.setLogBuffer(null);
        MDC.clear();
    }

    @Test
    void capturesBasicLogEvent() {
        testLogger.info("Hello world");

        List<LogEntry> entries = logBuffer.tail(10);
        assertEquals(1, entries.size());

        LogEntry entry = entries.getFirst();
        assertEquals("INFO", entry.level());
        assertEquals("Hello world", entry.message());
        assertEquals("TestClass", entry.source()); // short name from logger
        assertNull(entry.connection());
        assertNotNull(entry.timestamp());
    }

    @Test
    void capturesMdcScriptName() {
        MDC.put("script.name", "MyScript");
        testLogger.info("script log");

        List<LogEntry> entries = logBuffer.tail(10);
        assertEquals(1, entries.size());
        assertEquals("MyScript", entries.getFirst().source());
    }

    @Test
    void capturesMdcConnectionName() {
        MDC.put("connection.name", "Pipe1");
        testLogger.warn("with connection");

        List<LogEntry> entries = logBuffer.tail(10);
        assertEquals(1, entries.size());
        assertEquals("Pipe1", entries.getFirst().connection());
    }

    @Test
    void capturesBothMdcValues() {
        MDC.put("script.name", "Fisher");
        MDC.put("connection.name", "Pipe2");
        testLogger.error("both set");

        List<LogEntry> entries = logBuffer.tail(10);
        assertEquals(1, entries.size());
        LogEntry entry = entries.getFirst();
        assertEquals("Fisher", entry.source());
        assertEquals("Pipe2", entry.connection());
        assertEquals("ERROR", entry.level());
    }

    @Test
    void dropsEventsWhenNoBuffer() {
        LogBufferAppender.setLogBuffer(null);
        assertDoesNotThrow(() -> testLogger.info("should not throw"));
        // Re-set buffer and verify nothing was captured
        LogBufferAppender.setLogBuffer(logBuffer);
        assertEquals(0, logBuffer.size());
    }

    @Test
    void capturesMultipleEvents() {
        testLogger.info("first");
        testLogger.warn("second");
        testLogger.error("third");

        List<LogEntry> entries = logBuffer.tail(10);
        assertEquals(3, entries.size());
        assertEquals("first", entries.get(0).message());
        assertEquals("second", entries.get(1).message());
        assertEquals("third", entries.get(2).message());
    }

    @Test
    void shortensFullyQualifiedLoggerName() {
        // The logger is "com.botwithus.test.TestClass" — should be shortened to "TestClass"
        testLogger.info("test");

        List<LogEntry> entries = logBuffer.tail(10);
        assertEquals("TestClass", entries.getFirst().source());
    }

    @Test
    void preservesLogLevel() {
        testLogger.trace("t");
        testLogger.debug("d");
        testLogger.info("i");
        testLogger.warn("w");
        testLogger.error("e");

        List<LogEntry> entries = logBuffer.tail(10);
        assertEquals(5, entries.size());
        assertEquals("TRACE", entries.get(0).level());
        assertEquals("DEBUG", entries.get(1).level());
        assertEquals("INFO", entries.get(2).level());
        assertEquals("WARN", entries.get(3).level());
        assertEquals("ERROR", entries.get(4).level());
    }

    @Test
    void formatsMessageWithArguments() {
        testLogger.info("User {} logged in from {}", "Alice", "192.168.1.1");

        List<LogEntry> entries = logBuffer.tail(10);
        assertEquals(1, entries.size());
        assertEquals("User Alice logged in from 192.168.1.1", entries.getFirst().message());
    }
}
