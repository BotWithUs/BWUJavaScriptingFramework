package com.botwithus.bot.api.log;

import org.slf4j.Logger;

/**
 * {@link BotLogger} implementation that delegates to an SLF4J {@link Logger}.
 */
final class Slf4jBotLogger implements BotLogger {

    private final Logger delegate;

    Slf4jBotLogger(Logger delegate) {
        this.delegate = delegate;
    }

    @Override public void trace(String msg, Object... args) { delegate.trace(msg, args); }
    @Override public void debug(String msg, Object... args) { delegate.debug(msg, args); }
    @Override public void info(String msg, Object... args)  { delegate.info(msg, args); }
    @Override public void warn(String msg, Object... args)  { delegate.warn(msg, args); }
    @Override public void error(String msg, Object... args) { delegate.error(msg, args); }

    @Override
    public void error(String msg, Throwable t) {
        delegate.error(msg, t);
    }

    @Override
    public boolean isEnabled(LogLevel level) {
        return switch (level) {
            case TRACE -> delegate.isTraceEnabled();
            case DEBUG -> delegate.isDebugEnabled();
            case INFO  -> delegate.isInfoEnabled();
            case WARN  -> delegate.isWarnEnabled();
            case ERROR -> delegate.isErrorEnabled();
        };
    }
}
