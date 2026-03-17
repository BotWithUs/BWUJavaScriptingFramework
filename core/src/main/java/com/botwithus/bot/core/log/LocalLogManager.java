package com.botwithus.bot.core.log;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Mirrors stdout/stderr into rotating log files under {@code ~/.botwithus/logs/}.
 */
public final class LocalLogManager implements AutoCloseable {

    static final long DEFAULT_MAX_FILE_BYTES = Long.getLong("botwithus.log.maxBytes", 2L * 1024 * 1024);
    static final int DEFAULT_MAX_FILES_PER_STREAM = Integer.getInteger("botwithus.log.maxFilesPerStream", 5);
    static final int DEFAULT_MAX_TOTAL_FILES = Integer.getInteger("botwithus.log.maxTotalFiles", 20);

    private static final DateTimeFormatter SESSION_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static volatile LocalLogManager installed;

    private final Path baseDir;
    private final Path logDir;
    private final Path stdoutFile;
    private final Path stderrFile;
    private final RollingFileOutputStream stdoutSink;
    private final RollingFileOutputStream stderrSink;
    private final Object stdoutLock = new Object();
    private final Object stderrLock = new Object();
    private final int maxTotalFiles;
    private final Thread.UncaughtExceptionHandler previousUncaughtExceptionHandler;
    private final Thread.UncaughtExceptionHandler installedUncaughtExceptionHandler;
    private volatile boolean closed;

    private LocalLogManager(
            Path baseDir,
            String sessionPrefix,
            String sessionId,
            long maxFileBytes,
            int maxFilesPerStream,
            int maxTotalFiles,
            Thread.UncaughtExceptionHandler previousUncaughtExceptionHandler
    ) throws IOException {
        if (maxFileBytes < 1) {
            throw new IllegalArgumentException("maxFileBytes must be positive");
        }
        if (maxFilesPerStream < 1) {
            throw new IllegalArgumentException("maxFilesPerStream must be positive");
        }
        if (maxTotalFiles < 2) {
            throw new IllegalArgumentException("maxTotalFiles must be at least 2");
        }

        this.baseDir = baseDir;
        this.logDir = baseDir.resolve("logs");
        this.maxTotalFiles = maxTotalFiles;
        this.previousUncaughtExceptionHandler = previousUncaughtExceptionHandler;
        this.installedUncaughtExceptionHandler = this::handleUncaughtException;
        Files.createDirectories(logDir);

        String safePrefix = sanitize(sessionPrefix);
        String stdoutStem = safePrefix + "-" + sessionId + ".out";
        String stderrStem = safePrefix + "-" + sessionId + ".err";
        this.stdoutFile = logDir.resolve(stdoutStem + ".log");
        this.stderrFile = logDir.resolve(stderrStem + ".log");
        this.stdoutSink = new RollingFileOutputStream(stdoutStem, maxFileBytes, maxFilesPerStream);
        this.stderrSink = new RollingFileOutputStream(stderrStem, maxFileBytes, maxFilesPerStream);
        pruneLogDirectory();
    }

    public static synchronized LocalLogManager install(String sessionPrefix) {
        if (installed != null) {
            return installed;
        }

        try {
            LocalLogManager manager = create(
                    defaultBaseDir(),
                    sessionPrefix,
                    DEFAULT_MAX_FILE_BYTES,
                    DEFAULT_MAX_FILES_PER_STREAM,
                    DEFAULT_MAX_TOTAL_FILES,
                    Thread.getDefaultUncaughtExceptionHandler()
            );
            System.setOut(manager.wrapStdout(System.out));
            System.setErr(manager.wrapStderr(System.err));
            Thread.setDefaultUncaughtExceptionHandler(manager.installedUncaughtExceptionHandler);
            Runtime.getRuntime().addShutdownHook(new Thread(manager::closeQuietly, "jbot-local-log-shutdown"));
            installed = manager;
            return manager;
        } catch (IOException e) {
            System.err.println("[LocalLogManager] Failed to initialize local logs: " + e.getMessage());
            return null;
        }
    }

    public static LocalLogManager getInstalled() {
        return installed;
    }

    public static LocalLogManager create(Path baseDir, String sessionPrefix) throws IOException {
        return create(
                baseDir,
                sessionPrefix,
                DEFAULT_MAX_FILE_BYTES,
                DEFAULT_MAX_FILES_PER_STREAM,
                DEFAULT_MAX_TOTAL_FILES,
                null
        );
    }

    static LocalLogManager create(
            Path baseDir,
            String sessionPrefix,
            long maxFileBytes,
            int maxFilesPerStream,
            int maxTotalFiles,
            Thread.UncaughtExceptionHandler previousUncaughtExceptionHandler
    ) throws IOException {
        String sessionId = SESSION_FORMAT.format(LocalDateTime.now());
        return new LocalLogManager(
                baseDir,
                sessionPrefix,
                sessionId,
                maxFileBytes,
                maxFilesPerStream,
                maxTotalFiles,
                previousUncaughtExceptionHandler
        );
    }

    public static Path defaultBaseDir() {
        return Path.of(System.getProperty("user.home"), ".botwithus");
    }

    public PrintStream wrapStdout(PrintStream delegate) {
        return wrap(delegate, stdoutSink, stdoutLock);
    }

    public PrintStream wrapStderr(PrintStream delegate) {
        return wrap(delegate, stderrSink, stderrLock);
    }

    public Path getBaseDir() {
        return baseDir;
    }

    public Path getLogDir() {
        return logDir;
    }

    public Path getStdoutFile() {
        return stdoutFile;
    }

    public Path getStderrFile() {
        return stderrFile;
    }

    void logUncaughtException(Thread thread, Throwable error) {
        PrintStream err = System.err;
        String threadName = thread != null ? thread.getName() : "<unknown>";
        err.println("[CRASH] Uncaught exception in thread '" + threadName + "': " + error);
        error.printStackTrace(err);
        err.flush();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        if (Thread.getDefaultUncaughtExceptionHandler() == installedUncaughtExceptionHandler) {
            Thread.setDefaultUncaughtExceptionHandler(previousUncaughtExceptionHandler);
        }

        closeSink(stdoutSink);
        closeSink(stderrSink);
    }

    private void closeQuietly() {
        try {
            close();
        } catch (Exception ignored) {
        }
    }

    private void handleUncaughtException(Thread thread, Throwable error) {
        try {
            logUncaughtException(thread, error);
        } catch (Exception handlerError) {
            PrintStream fallback = System.err;
            fallback.println("[LocalLogManager] Failed to record uncaught exception: " + handlerError.getMessage());
        }

        if (previousUncaughtExceptionHandler != null) {
            try {
                previousUncaughtExceptionHandler.uncaughtException(thread, error);
            } catch (Exception handlerError) {
                System.err.println("[LocalLogManager] Previous uncaught exception handler failed: " + handlerError.getMessage());
            }
        }
    }

    private void pruneLogDirectory() throws IOException {
        try (Stream<Path> stream = Files.list(logDir)) {
            List<Path> logFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".log"))
                    .sorted(Comparator
                            .comparingLong(LocalLogManager::lastModifiedTime)
                            .thenComparing(path -> path.getFileName().toString()))
                    .toList();

            int removable = logFiles.size() - maxTotalFiles;
            if (removable <= 0) {
                return;
            }

            Set<Path> protectedFiles = Set.of(stdoutFile, stderrFile);
            for (Path path : logFiles) {
                if (removable <= 0) {
                    break;
                }
                if (protectedFiles.contains(path)) {
                    continue;
                }
                Files.deleteIfExists(path);
                removable--;
            }
        }
    }

    private static long lastModifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }

    private static PrintStream wrap(PrintStream delegate, OutputStream sink, Object lock) {
        Objects.requireNonNull(delegate, "delegate");
        return new PrintStream(new TeeOutputStream(delegate, sink, lock), true, StandardCharsets.UTF_8);
    }

    private static String sanitize(String sessionPrefix) {
        String safe = sessionPrefix == null ? "" : sessionPrefix.replaceAll("[^a-zA-Z0-9_\\-.]", "-");
        return safe.isBlank() ? "jbot" : safe;
    }

    private static void closeSink(OutputStream sink) {
        try {
            sink.flush();
            sink.close();
        } catch (IOException ignored) {
        }
    }

    private final class RollingFileOutputStream extends OutputStream {
        private final String stem;
        private final long maxFileBytes;
        private final int maxFilesPerStream;
        private OutputStream current;
        private long currentSize;

        private RollingFileOutputStream(String stem, long maxFileBytes, int maxFilesPerStream) throws IOException {
            this.stem = stem;
            this.maxFileBytes = maxFileBytes;
            this.maxFilesPerStream = maxFilesPerStream;
            openCurrentFile();
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len <= 0) {
                return;
            }
            rotateIfNeeded(len);
            current.write(b, off, len);
            currentSize += len;
        }

        @Override
        public void flush() throws IOException {
            current.flush();
        }

        @Override
        public void close() throws IOException {
            current.flush();
            current.close();
        }

        private void rotateIfNeeded(int nextWriteBytes) throws IOException {
            if (currentSize == 0 || currentSize + nextWriteBytes <= maxFileBytes) {
                return;
            }

            current.flush();
            current.close();

            if (maxFilesPerStream == 1) {
                Files.deleteIfExists(pathForIndex(0));
            } else {
                Files.deleteIfExists(pathForIndex(maxFilesPerStream - 1));
                for (int i = maxFilesPerStream - 1; i >= 1; i--) {
                    Path source = i == 1 ? pathForIndex(0) : pathForIndex(i - 1);
                    if (Files.exists(source)) {
                        Files.move(source, pathForIndex(i), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

            openCurrentFile();
            pruneLogDirectory();
        }

        private void openCurrentFile() throws IOException {
            Path currentFile = pathForIndex(0);
            current = Files.newOutputStream(currentFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            currentSize = Files.exists(currentFile) ? Files.size(currentFile) : 0L;
        }

        private Path pathForIndex(int index) {
            return index == 0
                    ? logDir.resolve(stem + ".log")
                    : logDir.resolve(stem + "." + index + ".log");
        }
    }

    private static final class TeeOutputStream extends OutputStream {
        private final PrintStream delegate;
        private final OutputStream sink;
        private final Object lock;

        private TeeOutputStream(PrintStream delegate, OutputStream sink, Object lock) {
            this.delegate = delegate;
            this.sink = sink;
            this.lock = lock;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            synchronized (lock) {
                sink.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            synchronized (lock) {
                sink.write(b, off, len);
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
            synchronized (lock) {
                sink.flush();
            }
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
