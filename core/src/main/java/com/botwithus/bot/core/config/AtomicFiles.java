package com.botwithus.bot.core.config;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Replaces a small host file so that no reader, and no crash, ever sees half of it.
 */
public final class AtomicFiles {

    /**
     * How often to try the final rename. Windows refuses to rename over a file that
     * another handle has open, and a second host reading the same file holds one
     * for a moment; the retries wait that moment out.
     */
    private static final int RENAME_ATTEMPTS = 10;
    private static final long RENAME_RETRY_MILLIS = 20;
    private static final String TEMP_SUFFIX = ".tmp";

    /** Produces a file's new contents. */
    @FunctionalInterface
    public interface Content {
        void writeTo(OutputStream out) throws IOException;
    }

    private AtomicFiles() {
    }

    /** Writes {@code data} to {@code target}; see {@link #write(Path, Content)}. */
    public static void write(Path target, byte[] data) throws IOException {
        write(target, out -> out.write(data));
    }

    /**
     * Writes what {@code content} produces to {@code target}, creating its directory
     * if needed.
     *
     * <p>The bytes go to a new file beside the target, are flushed to disk, and the
     * file is then renamed over the target. A reader sees the old file or the new
     * one, never a mix, and a failure part-way through, in {@code content} or in the
     * process, leaves the old file whole. The temporary file is uniquely named, so
     * two processes writing the same target never share one. If the rename cannot
     * be made the call fails; it never falls back to rewriting the target in place.
     */
    public static void write(Path target, Content content) throws IOException {
        Path directory = target.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, target.getFileName() + ".", TEMP_SUFFIX);
        try {
            writeDurably(temp, content);
            replace(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void writeDurably(Path file, Content content) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
             OutputStream out = Channels.newOutputStream(channel)) {
            content.writeTo(out);
            out.flush();
            channel.force(true);
        }
    }

    private static void replace(Path source, Path target) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                move(source, target);
                return;
            } catch (AccessDeniedException e) {
                if (attempt == RENAME_ATTEMPTS) {
                    throw e;
                }
                pause();
            }
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Still a rename within one directory, just without the filesystem's
            // guarantee; far better than truncating the target and rewriting it.
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void pause() throws InterruptedIOException {
        try {
            Thread.sleep(RENAME_RETRY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while replacing a file.");
        }
    }
}
