package com.botwithus.bot.api.log;

/**
 * Factory for obtaining {@link BotLogger} instances.
 * Delegates to SLF4J under the hood.
 */
public final class LoggerFactory {

    private LoggerFactory() {}

    public static BotLogger getLogger(String name) {
        return new Slf4jBotLogger(org.slf4j.LoggerFactory.getLogger(name));
    }

    public static BotLogger getLogger(Class<?> clazz) {
        return new Slf4jBotLogger(org.slf4j.LoggerFactory.getLogger(clazz));
    }
}
