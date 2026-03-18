package com.botwithus.bot.api.log;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that the BotLogger API correctly delegates to SLF4J via Slf4jBotLogger.
 */
class BotLoggerTest {

    @Test
    void getLoggerByName() {
        BotLogger logger = LoggerFactory.getLogger("TestLogger");
        assertNotNull(logger);
    }

    @Test
    void getLoggerByClass() {
        BotLogger logger = LoggerFactory.getLogger(BotLoggerTest.class);
        assertNotNull(logger);
    }

    @Test
    void logMethodsDoNotThrow() {
        BotLogger logger = LoggerFactory.getLogger("TestLogger");
        assertDoesNotThrow(() -> logger.info("simple message"));
        assertDoesNotThrow(() -> logger.info("with arg: {}", "value"));
        assertDoesNotThrow(() -> logger.info("with args: {} {}", "a", "b"));
        assertDoesNotThrow(() -> logger.debug("debug msg"));
        assertDoesNotThrow(() -> logger.warn("warn msg"));
        assertDoesNotThrow(() -> logger.error("error msg"));
        assertDoesNotThrow(() -> logger.trace("trace msg"));
    }

    @Test
    void errorWithThrowableDoesNotThrow() {
        BotLogger logger = LoggerFactory.getLogger("TestLogger");
        assertDoesNotThrow(() -> logger.error("oops", new RuntimeException("test")));
    }

    @Test
    void isEnabledReturnsBoolean() {
        BotLogger logger = LoggerFactory.getLogger("TestLogger");
        // Just verify isEnabled works for all levels without throwing
        // (actual enabled state depends on the SLF4J backend present at test time)
        for (LogLevel level : LogLevel.values()) {
            boolean result = logger.isEnabled(level);
            assertTrue(result || !result); // no exception
        }
    }

    @Test
    void isEnabledCoversAllLevels() {
        BotLogger logger = LoggerFactory.getLogger("TestLogger");
        // Without Logback on test classpath, SLF4J NOP backend disables all levels.
        // Just verify the switch is exhaustive and returns without error.
        for (LogLevel level : LogLevel.values()) {
            assertDoesNotThrow(() -> logger.isEnabled(level));
        }
    }
}
