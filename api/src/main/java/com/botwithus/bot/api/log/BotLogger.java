package com.botwithus.bot.api.log;

/**
 * Simple logging interface for bot scripts and framework components.
 */
public interface BotLogger {

    void trace(String msg, Object... args);
    void debug(String msg, Object... args);
    void info(String msg, Object... args);
    void warn(String msg, Object... args);
    void error(String msg, Object... args);
    void error(String msg, Throwable t);
    boolean isEnabled(LogLevel level);
}
