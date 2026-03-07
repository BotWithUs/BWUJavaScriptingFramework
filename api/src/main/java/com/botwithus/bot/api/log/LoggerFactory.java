package com.botwithus.bot.api.log;

import java.util.function.Function;

/**
 * Factory for obtaining {@link BotLogger} instances.
 * Falls back to a simple System.out logger if no provider is set.
 */
public final class LoggerFactory {

    private static volatile Function<String, BotLogger> provider = SystemOutLogger::new;

    private LoggerFactory() {}

    public static void setProvider(Function<String, BotLogger> provider) {
        LoggerFactory.provider = provider;
    }

    public static BotLogger getLogger(String name) {
        return provider.apply(name);
    }

    public static BotLogger getLogger(Class<?> clazz) {
        return getLogger(clazz.getSimpleName());
    }

    /**
     * Default logger that writes to System.out / System.err.
     */
    private static class SystemOutLogger implements BotLogger {
        private final String name;

        SystemOutLogger(String name) {
            this.name = name;
        }

        @Override public void trace(String msg, Object... args) { log(LogLevel.TRACE, msg, args); }
        @Override public void debug(String msg, Object... args) { log(LogLevel.DEBUG, msg, args); }
        @Override public void info(String msg, Object... args)  { log(LogLevel.INFO, msg, args); }
        @Override public void warn(String msg, Object... args)  { log(LogLevel.WARN, msg, args); }
        @Override public void error(String msg, Object... args) { log(LogLevel.ERROR, msg, args); }

        @Override
        public void error(String msg, Throwable t) {
            System.err.println("[ERROR] [" + name + "] " + msg);
            t.printStackTrace(System.err);
        }

        @Override
        public boolean isEnabled(LogLevel level) {
            return level.ordinal() >= LogLevel.INFO.ordinal();
        }

        private void log(LogLevel level, String msg, Object... args) {
            if (!isEnabled(level)) return;
            String formatted = args.length > 0 ? String.format(msg.replace("{}", "%s"), args) : msg;
            var out = level.ordinal() >= LogLevel.WARN.ordinal() ? System.err : System.out;
            out.println("[" + level + "] [" + name + "] " + formatted);
        }
    }
}
