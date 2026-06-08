package com.botwithus.bot.cli.log;

import com.botwithus.bot.core.runtime.ConnectionContext;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.function.Predicate;

public class LogCapture {

    private final PrintStream originalOut;
    private final PrintStream originalErr;
    private final LogBuffer logBuffer;
    private volatile Predicate<String> connectionFilter;

    public LogCapture(LogBuffer logBuffer) {
        this.logBuffer = logBuffer;
        this.originalOut = System.out;
        this.originalErr = System.err;
    }

    /** Constructor for GUI mode: uses custom PrintStreams instead of System.out/err. */
    public LogCapture(LogBuffer logBuffer, PrintStream customOut, PrintStream customErr) {
        this.logBuffer = logBuffer;
        this.originalOut = customOut;
        this.originalErr = customErr;
    }

    public void install() {
        System.setOut(new CapturingPrintStream(originalOut, logBuffer, "stdout", "INFO", this));
        System.setErr(new CapturingPrintStream(originalErr, logBuffer, "stderr", "ERROR", this));
    }

    public void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    public PrintStream getOriginalOut() {
        return originalOut;
    }

    public PrintStream getOriginalErr() {
        return originalErr;
    }

    public void setConnectionFilter(Predicate<String> filter) {
        this.connectionFilter = filter;
    }

    public Predicate<String> getConnectionFilter() {
        return connectionFilter;
    }

    private static class CapturingPrintStream extends PrintStream {
        CapturingPrintStream(PrintStream original, LogBuffer logBuffer, String source, String level, LogCapture capture) {
            super(new TeeOutputStream(original, logBuffer, source, level, capture));
        }
    }

    private static class TeeOutputStream extends OutputStream {
        private final PrintStream original;
        private final LogBuffer logBuffer;
        private final String source;
        private final String level;
        private final LogCapture capture;
        private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();

        TeeOutputStream(PrintStream original, LogBuffer logBuffer, String source, String level, LogCapture capture) {
            this.original = original;
            this.logBuffer = logBuffer;
            this.source = source;
            this.level = level;
            this.capture = capture;
        }

        @Override
        public void write(int b) {
            if (b == '\n') {
                flushLine();
            } else {
                lineBuffer.write(b);
            }
        }

        @Override
        public void write(byte[] buf, int off, int len) {
            for (int i = off; i < off + len; i++) {
                write(buf[i]);
            }
        }

        @Override
        public void flush() {
            original.flush();
        }

        private void flushLine() {
            String line = lineBuffer.toString();
            lineBuffer.reset();

            // rule-exception: {rule:no-singleton} — ConnectionContext is the documented
            // request-context carve-out (see CLAUDE.md §"Java rules exceptions").
            // LogCapture reads the connection-name tag from the print-stream thread,
            // which is set by ScriptRunner / RpcClient on the producing side.
            String connection = ConnectionContext.get();

            if (!line.isEmpty()) {
                logBuffer.add(new LogEntry(source, level, line, connection));
            }

            // Apply connection filter: print to original if no filter, or connection is null
            // (system message), or the filter matches.
            Predicate<String> filter = capture.connectionFilter;
            if (filter == null || connection == null || filter.test(connection)) {
                original.println(line);
            }
        }
    }
}
