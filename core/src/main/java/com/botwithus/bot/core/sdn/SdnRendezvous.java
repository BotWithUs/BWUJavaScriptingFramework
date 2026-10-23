package com.botwithus.bot.core.sdn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * File-courier rendezvous primitives shared by every launcher-to-host exchange.
 *
 * <p>The host and the launcher meet in one directory. The host writes a request
 * file named after its own pid; the launcher answers with a reply file named the
 * same way. Every write lands atomically, so a reader never observes a partial
 * file, and both sides delete what they have consumed.
 *
 * <p>This class owns only the mechanics — where the directory is, how to publish
 * a file without tearing, how to wait for one, and who may run an exchange. What
 * the files mean belongs to the exchange that uses them.
 *
 * <p>Every file is named after the pid, so an exchange has exactly one request
 * slot per process. Two callers running the same exchange at once would overwrite
 * each other's request and delete it in cleanup, so each exchange runs under its
 * {@link #exchangeLock}, one caller at a time.
 */
public final class SdnRendezvous {

    private static final Logger log = LoggerFactory.getLogger(SdnRendezvous.class);

    /** Optional shared-directory override (absolute path). */
    public static final String DIR_PROP = "botwithus.sdn.dir";

    private static final String TMP_SUFFIX = ".tmp";
    private static final long POLL_MILLIS = 100;

    // rule-exception: mutable static — see CLAUDE.md "Java rules exceptions". The files
    // these locks guard are named after the pid, so they are process-global; an injected
    // lock would only exclude the callers that happened to be wired with the same one.
    private static final ConcurrentMap<Exchange, ReentrantLock> EXCHANGE_LOCKS =
            new ConcurrentHashMap<>();

    /** One exchange: the directory it runs in and the suffix of its request file. */
    private record Exchange(Path directory, String requestSuffix) {
    }

    private SdnRendezvous() {
    }

    /**
     * The lock a caller holds from writing an exchange's request until it has deleted
     * it. Every caller in this process naming the same directory and request suffix
     * gets the same lock, whichever object it came through.
     *
     * <p>Fair, so queued callers run in the order they asked.
     */
    static ReentrantLock exchangeLock(Path directory, String requestSuffix) {
        Exchange exchange = new Exchange(directory.toAbsolutePath().normalize(), requestSuffix);
        return EXCHANGE_LOCKS.computeIfAbsent(exchange, e -> new ReentrantLock(true));
    }

    /** The shared directory the courier and the host rendezvous in. */
    public static Path directory() {
        String override = System.getProperty(DIR_PROP);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".botwithus", "sdn");
    }

    /** This JVM's pid, which names every file in an exchange it initiates. */
    public static long currentPid() {
        return ProcessHandle.current().pid();
    }

    /**
     * Reads {@code file} as soon as it appears, or returns null if it has not
     * appeared before {@code timeout} elapses.
     */
    public static byte[] awaitFile(Path file, Duration timeout)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(file)) {
                return Files.readAllBytes(file);
            }
            Thread.sleep(POLL_MILLIS);
        }
        return null;
    }

    /**
     * Publishes {@code data} at {@code target} so that no reader ever sees a
     * partially written file. Falls back to a plain move on filesystems that
     * cannot rename atomically.
     */
    public static void atomicWrite(Path target, byte[] data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + TMP_SUFFIX);
        Files.write(tmp, data);
        try {
            Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Deletes a consumed rendezvous file; a failure here is never worth raising. */
    public static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.debug("SDN: could not delete {}: {}", p, e.getMessage());
        }
    }
}
