package com.botwithus.bot.core.runlog;

import ch.qos.logback.classic.spi.ThrowableProxy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * One run of one script: its log file (if it could be created), its redactor
 * and its breadcrumbs. Obtained from {@link RunLogs#open}; everything the run
 * writes goes through here, so every byte passes the redactor first.
 *
 * <p>Writes after {@link #close()} are dropped. A run whose file could not be
 * created still keeps breadcrumbs and a crash summary, so a report can carry the
 * crash even when the disk refused the log.</p>
 */
public final class ScriptRun implements AutoCloseable {

    static final String CRASH_OPEN = "=== CRASH phase=%s iteration=%d at=%s ===";
    static final String CRASH_CRUMBS = "=== BREADCRUMBS last=%d ===";
    static final String CRASH_END = "=== END ===";
    private static final String LEVEL_FORMAT = "%-5s";

    private final String runId;
    private final RunKey key;
    private final RunLogFile file;
    private final Redactor redactor;
    private final Breadcrumbs breadcrumbs;
    private final Clock clock;
    private final Predicate<StackTraceElement> isScriptFrame;
    private final Consumer<CrashSummary> crashSink;
    private final Consumer<ScriptRun> onClose;
    private volatile boolean isClosed;

    ScriptRun(RunParts parts) {
        this.runId = parts.runId();
        this.key = parts.key();
        this.file = parts.file();
        this.redactor = parts.redactor();
        this.breadcrumbs = new Breadcrumbs(parts.clock());
        this.clock = parts.clock();
        this.isScriptFrame = parts.isScriptFrame();
        this.crashSink = parts.crashSink();
        this.onClose = parts.onClose();
    }

    /** Everything a run is built from; {@code file} may be null when it could not be created. */
    record RunParts(String runId, RunKey key, RunLogFile file, Redactor redactor, Clock clock,
                    Predicate<StackTraceElement> isScriptFrame, Consumer<CrashSummary> crashSink,
                    Consumer<ScriptRun> onClose) {
    }

    /** The 128-bit run id as 32 lowercase hex characters. */
    public String runId() {
        return runId;
    }

    /** The run's log file, or empty when it could not be created. */
    public Optional<Path> logFile() {
        return Optional.ofNullable(file).map(RunLogFile::path);
    }

    /** Appends one log line (spec §1.2) and any throwable lines that follow it. */
    public void log(Instant at, String level, String thread, String logger, String message,
                    List<String> throwableLines) {
        if (isClosed || file == null) {
            return;
        }
        StringBuilder text = new StringBuilder()
                .append(RunLogClock.format(at)).append(' ')
                .append(String.format(Locale.ROOT, LEVEL_FORMAT, level)).append(' ')
                .append('[').append(thread).append("] ")
                .append(logger).append(": ").append(message == null ? "" : message);
        for (String line : throwableLines) {
            text.append('\n').append(line);
        }
        file.writeLines(text.toString());
    }

    /** Records a breadcrumb: {@code rpc}, {@code state}, {@code tile} or {@code thread}. */
    public void crumb(String kind, String detail) {
        if (!isClosed) {
            breadcrumbs.add(kind, detail);
        }
    }

    /**
     * Writes the spec §1.3 crash block, past the size cap, and publishes the
     * summary a report will carry. Safe to call right after a
     * {@link StackOverflowError}: the stack has unwound to the runner's catch.
     */
    public CrashSummary crash(CrashPhase phase, long iteration, Throwable error) {
        Instant at = clock.instant();
        ThrowableProxy proxy = new ThrowableProxy(error);
        String exception = redactor.redact(ThrowableText.headline(proxy).split("\\R", -1)[0]);
        String topFrame = redactor.redact(topFrame(error));
        List<String> stack = ThrowableText.lines(proxy).stream().map(redactor::redact).toList();
        List<String> crumbs = breadcrumbs.render(redactor);
        List<String> block = new ArrayList<>();
        block.add(String.format(Locale.ROOT, CRASH_OPEN, phase.wireName(), iteration,
                RunLogClock.format(at)));
        block.add("exception: " + exception);
        block.add("top_frame: " + topFrame);
        block.addAll(stack);
        block.add(String.format(Locale.ROOT, CRASH_CRUMBS, crumbs.size()));
        block.addAll(crumbs);
        block.add(CRASH_END);
        if (file != null && !isClosed) {
            file.writeRedacted(block);
        }
        CrashSummary summary = new CrashSummary(runId, logFile(), phase, iteration, at, exception,
                topFrame, String.join("\n", stack), crumbs);
        crashSink.accept(summary);
        return summary;
    }

    /** {@code false} once the run has ended. */
    public boolean isOpen() {
        return !isClosed;
    }

    /** Ends the run: flushes and closes the file. Idempotent. */
    @Override
    public void close() {
        if (isClosed) {
            return;
        }
        isClosed = true;
        if (file != null) {
            file.close();
        }
        onClose.accept(this);
    }

    RunKey key() {
        return key;
    }

    /** The first frame, through the cause chain, that is script code; {@code unknown} if none. */
    private String topFrame(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < ThrowableText.MAX_NESTED) {
            for (StackTraceElement frame : current.getStackTrace()) {
                if (isScriptFrame.test(frame)) {
                    return plain(frame);
                }
            }
            current = current.getCause() == current ? null : current.getCause();
            depth++;
        }
        return RunLogHeader.UNKNOWN;
    }

    /** {@code com.example.Foo.loop(Foo.java:88)}: no loader or module prefix, as the spec shows it. */
    static String plain(StackTraceElement frame) {
        String where;
        if (frame.isNativeMethod()) {
            where = "Native Method";
        } else if (frame.getFileName() == null) {
            where = "Unknown Source";
        } else if (frame.getLineNumber() >= 0) {
            where = frame.getFileName() + ":" + frame.getLineNumber();
        } else {
            where = frame.getFileName();
        }
        return frame.getClassName() + "." + frame.getMethodName() + "(" + where + ")";
    }
}
