package com.botwithus.bot.api.log;

/**
 * Factory for obtaining {@link BotLogger} instances.
 * Delegates to SLF4J under the hood.
 */
public final class LoggerFactory {

    private LoggerFactory() {}

    public static BotLogger getLogger(String name) {
        // FQN intentional: this class shadows org.slf4j.LoggerFactory by name.
        // Concentrated to one site; the Class overload delegates here.
        return new Slf4jBotLogger(org.slf4j.LoggerFactory.getLogger(name));
    }

    public static BotLogger getLogger(Class<?> clazz) {
        return getLogger(clazz.getName());
    }
}
