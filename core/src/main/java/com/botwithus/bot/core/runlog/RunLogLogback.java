package com.botwithus.bot.core.runlog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Connects logback to {@link RunLogs}. Three pieces, installed by {@link #install}:
 *
 * <ul>
 *   <li><b>A turbo filter</b> that lets DEBUG through on a thread belonging to an
 *   open run, and only there. The root logger stays at INFO, so host threads pay
 *   nothing for their DEBUG statements; the console and the GUI buffer carry
 *   their own INFO threshold in {@code logback.xml}, so the extra DEBUG goes to
 *   the run log alone.</li>
 *   <li><b>A first appender</b> that writes the event into the calling thread's
 *   run and marks the thread as "inside a logging event".</li>
 *   <li><b>A last appender</b> that clears the mark.</li>
 * </ul>
 *
 * <p>The mark is what keeps a run log free of duplicates. Logback's console
 * appender writes to whatever {@code System.out} is at the time, and the host
 * replaces {@code System.out} with a tee that forwards a script thread's
 * printing to its run log. Without the mark every log line on a script thread
 * would come back a second time as a "stdout" line.</p>
 */
public final class RunLogLogback {

    static final String FIRST_APPENDER = "RUN_LOG";
    static final String LAST_APPENDER = "RUN_LOG_END";

    private RunLogLogback() {
    }

    /**
     * Installs the filter and the bracketing appenders on logback's root logger,
     * around whatever appenders it already has. Call once, at startup, after
     * logback has read its configuration. Does nothing, and says so, when SLF4J
     * is bound to something other than logback.
     *
     * @return whether it was installed
     */
    public static boolean install(RunLogs runLogs) {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        // rule-exception: {rule:no-instanceof} — SLF4J/logback binding boundary,
        // the same seam ImGuiApp.wireLogBufferAppender crosses. getILoggerFactory()
        // is typed ILoggerFactory; logback's implementation is LoggerContext and
        // there is no other way to reach it.
        if (!(factory instanceof LoggerContext context)) {
            LoggerFactory.getLogger(RunLogLogback.class)
                    .warn("SLF4J is not bound to logback; run logs will not capture log lines");
            return false;
        }
        install(context, runLogs);
        return true;
    }

    private static void install(LoggerContext context, RunLogs runLogs) {
        ScriptDebugFilter filter = new ScriptDebugFilter(runLogs);
        filter.setContext(context);
        filter.start();
        context.addTurboFilter(filter);

        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        List<Appender<ILoggingEvent>> existing = new ArrayList<>();
        Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders();
        it.forEachRemaining(existing::add);
        existing.forEach(root::detachAppender);
        root.addAppender(started(new RunLogAppender(runLogs), FIRST_APPENDER, context));
        existing.forEach(root::addAppender);
        root.addAppender(started(new EchoEndAppender(runLogs), LAST_APPENDER, context));
    }

    private static Appender<ILoggingEvent> started(UnsynchronizedAppenderBase<ILoggingEvent> appender, String name,
                                                   LoggerContext context) {
        appender.setName(name);
        appender.setContext(context);
        appender.start();
        return appender;
    }

    /** Lets DEBUG through for a thread that belongs to an open run. */
    private static final class ScriptDebugFilter extends TurboFilter {

        private final RunLogs runLogs;

        ScriptDebugFilter(RunLogs runLogs) {
            this.runLogs = runLogs;
        }

        @Override
        public FilterReply decide(Marker marker, Logger logger, Level level,
                                  String format, Object[] params, Throwable t) {
            if (level != Level.DEBUG) {
                return FilterReply.NEUTRAL;
            }
            return runLogs.current().filter(ScriptRun::isOpen).isPresent()
                    ? FilterReply.ACCEPT : FilterReply.NEUTRAL;
        }
    }

    /** Writes the event into the calling thread's run, and opens the echo mark. */
    private static final class RunLogAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

        private final RunLogs runLogs;

        RunLogAppender(RunLogs runLogs) {
            this.runLogs = runLogs;
        }

        @Override
        protected void append(ILoggingEvent event) {
            runLogs.beginLogEvent();
            runLogs.current().ifPresent(run -> run.log(Instant.ofEpochMilli(event.getTimeStamp()),
                    event.getLevel().toString(), event.getThreadName(), event.getLoggerName(),
                    event.getFormattedMessage(), ThrowableText.lines(event.getThrowableProxy())));
        }
    }

    /** Closes the echo mark once every other appender has run. */
    private static final class EchoEndAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

        private final RunLogs runLogs;

        EchoEndAppender(RunLogs runLogs) {
            this.runLogs = runLogs;
        }

        @Override
        protected void append(ILoggingEvent event) {
            runLogs.endLogEvent();
        }
    }
}
