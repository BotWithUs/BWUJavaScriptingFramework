package com.botwithus.bot.cli.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.time.Instant;

/**
 * Logback appender that feeds logging events into the GUI {@link LogBuffer}.
 * <p>
 * Call {@link #setLogBuffer(LogBuffer)} early at startup so events are captured.
 * Events received before a buffer is set are silently dropped.
 */
public class LogBufferAppender extends AppenderBase<ILoggingEvent> {

    private static volatile LogBuffer logBuffer;

    public static void setLogBuffer(LogBuffer buffer) {
        logBuffer = buffer;
    }

    @Override
    protected void append(ILoggingEvent event) {
        LogBuffer buf = logBuffer;
        if (buf == null) return;

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
                if (dot >= 0) source = source.substring(dot + 1);
            } else {
                source = "unknown";
            }
        }

        String level = event.getLevel().toString();
        String message = event.getFormattedMessage();

        buf.add(new LogEntry(
                Instant.ofEpochMilli(event.getTimeStamp()),
                source, level, message, connection));
    }
}
