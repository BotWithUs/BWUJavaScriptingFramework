package com.botwithus.bot.core.runlog;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * One run's log file: UTF-8, LF line endings, every line through the run's
 * {@link Redactor} before it is encoded, and a {@value #MAX_BYTES}-byte cap.
 *
 * <p>On reaching the cap the file gets {@link #TRUNCATED_MARKER} once and drops
 * every later ordinary line; a crash block ({@link #writeRedacted}) is still written past it, because
 * the crash is the part of the file a report exists for (spec §1).</p>
 *
 * <p>Each write is flushed, so a host that is killed mid-run leaves a log that
 * ends at the last line written rather than at the last full buffer.</p>
 *
 * <p>Thread-safe. After {@link #close()} or an I/O failure every write is a no-op.</p>
 */
final class RunLogFile implements AutoCloseable {

    /** Per-file cap from the spec: 5 MB. */
    static final long MAX_BYTES = 5L * 1024 * 1024;
    static final String TRUNCATED_MARKER = "[log truncated at 5 MB]";
    private static final byte[] NEWLINE = {'\n'};
    private static final long MARKER_BYTES =
            TRUNCATED_MARKER.getBytes(StandardCharsets.UTF_8).length + NEWLINE.length;

    private final Path path;
    private final Redactor redactor;
    private final long maxBytes;
    private Writer out;
    private long written;
    private boolean isTruncated;

    private RunLogFile(Path path, Writer out, Redactor redactor, long maxBytes) {
        this.path = path;
        this.out = out;
        this.redactor = redactor;
        this.maxBytes = maxBytes;
    }

    /** Creates {@code path} (which must not exist yet) and its parent directories. */
    static RunLogFile create(Path path, Redactor redactor) throws IOException {
        return create(path, redactor, MAX_BYTES);
    }

    /** {@link #create(Path, Redactor)} with a smaller cap; the cap tests use it. */
    static RunLogFile create(Path path, Redactor redactor, long maxBytes) throws IOException {
        Files.createDirectories(path.getParent());
        Writer writer = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                StandardCharsets.UTF_8));
        return new RunLogFile(path, writer, redactor, maxBytes);
    }

    Path path() {
        return path;
    }

    /**
     * Writes lines the caller has already put through the run's redactor (the
     * header, and a crash block whose redacted text is also kept for reports),
     * whatever the cap says.
     */
    synchronized void writeRedacted(List<String> redactedLines) {
        for (String line : redactedLines) {
            emit(line);
        }
        flush();
    }

    /** Writes {@code text}, which may hold several lines, subject to the cap. */
    synchronized void writeLines(String text) {
        if (out == null || isTruncated) {
            return;
        }
        for (String raw : text.split("\\R", -1)) {
            String line = redactor.redact(raw);
            long size = sizeOf(line);
            if (written + size > maxBytes - MARKER_BYTES) {
                emit(TRUNCATED_MARKER);
                isTruncated = true;
                break;
            }
            emit(line);
        }
        flush();
    }

    @Override
    public synchronized void close() {
        if (out == null) {
            return;
        }
        try {
            out.close();
        } catch (IOException e) {
            // Nothing further can be written either way.
        }
        out = null;
    }

    private static long sizeOf(String line) {
        return line.getBytes(StandardCharsets.UTF_8).length + (long) NEWLINE.length;
    }

    private void emit(String line) {
        if (out == null) {
            return;
        }
        try {
            out.write(line);
            out.write('\n');
            written += sizeOf(line);
        } catch (IOException e) {
            close();
        }
    }

    private void flush() {
        if (out == null) {
            return;
        }
        try {
            out.flush();
        } catch (IOException e) {
            close();
        }
    }
}
