package com.botwithus.bot.core.log;

import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LogLevel;

import java.io.PrintStream;

/**
 * Logger backed by PrintStream (System.out/err), compatible with LogCapture.
 */
public class PrintStreamLogger implements BotLogger {

    private final String name;
    private final PrintStream out;
    private final PrintStream err;
    private volatile LogLevel threshold = LogLevel.INFO;

    public PrintStreamLogger(String name) {
        this(name, System.out, System.err);
    }

    public PrintStreamLogger(String name, PrintStream out, PrintStream err) {
        this.name = name;
        this.out = out;
        this.err = err;
    }

    public void setThreshold(LogLevel threshold) {
        this.threshold = threshold;
    }

    @Override public void trace(String msg, Object... args) { log(LogLevel.TRACE, msg, args); }
    @Override public void debug(String msg, Object... args) { log(LogLevel.DEBUG, msg, args); }
    @Override public void info(String msg, Object... args)  { log(LogLevel.INFO, msg, args); }
    @Override public void warn(String msg, Object... args)  { log(LogLevel.WARN, msg, args); }
    @Override public void error(String msg, Object... args) { log(LogLevel.ERROR, msg, args); }

    @Override
    public void error(String msg, Throwable t) {
        err.println("[ERROR] [" + name + "] " + msg);
        t.printStackTrace(err);
    }

    @Override
    public boolean isEnabled(LogLevel level) {
        return level.ordinal() >= threshold.ordinal();
    }

    private void log(LogLevel level, String msg, Object... args) {
        if (!isEnabled(level)) return;
        String formatted = args.length > 0 ? String.format(msg.replace("{}", "%s"), args) : msg;
        PrintStream target = level.ordinal() >= LogLevel.WARN.ordinal() ? err : out;
        target.println("[" + level + "] [" + name + "] " + formatted);
    }
}
