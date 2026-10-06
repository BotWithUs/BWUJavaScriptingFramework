package com.botwithus.bot.cli.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;

import java.time.Instant;

/**
 * Logback appender that feeds logging events into the GUI {@link LogBuffer}.
 * <p>
 * Instantiated by Logback via {@code logback.xml}; the application looks up
 * the appender by name from the {@link ch.qos.logback.classic.LoggerContext}
 * and calls {@link #setLogBuffer(LogBuffer)} on it. Events received before a
 * buffer is set are silently dropped.
 */
public class LogBufferAppender extends AppenderBase<ILoggingEvent> {

    public LogBufferAppender() {}

    private volatile LogBuffer logBuffer;

    public void setLogBuffer(LogBuffer buffer) {
        this.logBuffer = buffer;
    }

    @Override
    protected void append(ILoggingEvent event) {
        LogBuffer buf = logBuffer;
        if (buf == null) {
            return;
        }

        var mdc = event.getMDCPropertyMap();
        String scriptName = mdc.get("script.name");
        String connection = mdc.get("connection.name");

        String source;
        if (scriptName != null) {
            source = scriptName;
        } else {
            source = event.getLoggerName();
            if (source != null) {
                int dot = source.lastIndexOf('.');
                if (dot >= 0) {
                    source = source.substring(dot + 1);
                }
            } else {
                source = "unknown";
            }
        }

        String level = event.getLevel().toString();
        String message = withThrowable(event.getFormattedMessage(), event.getThrowableProxy());

        buf.add(new LogEntry(
                Instant.ofEpochMilli(event.getTimeStamp()),
                source, level, message, connection));
    }

    /**
     * The message followed by the full trace of an attached throwable. Without
     * this, {@code log.error("...", e)} reached the GUI as the message alone and
     * the trace was only ever on the console.
     */
    private static String withThrowable(String message, IThrowableProxy throwable) {
        if (throwable == null) {
            return message;
        }
        return message + System.lineSeparator() + ThrowableProxyUtil.asString(throwable);
    }
}
