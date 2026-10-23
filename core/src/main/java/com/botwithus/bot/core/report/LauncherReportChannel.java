package com.botwithus.bot.core.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Hands a problem report to the BotWithUs launcher, which bundles the logs,
 * uploads them and answers with a code. The two processes meet in a directory
 * ({@code ~/.botwithus/reports/}, or {@value #DIR_ENV}) through four files per
 * request:
 *
 * <ol>
 *   <li>the host writes {@code <id>.rptreq}, atomically (a {@code .tmp} renamed
 *   into place), so the launcher never reads half of one;</li>
 *   <li>the launcher claims it: deletes it and writes {@code <id>.rptwork};</li>
 *   <li>the launcher writes {@code <id>.rpt}, atomically, and deletes the
 *   {@code .rptwork};</li>
 *   <li>the host reads the {@code .rpt} and deletes it.</li>
 * </ol>
 *
 * <p>A request nobody claims within {@link Timing#claimWindow()} means no
 * launcher is running: the host takes its request back and says so. Once claimed,
 * the host waits up to {@link Timing#replyWindow()}. Nobody retries; one request
 * is one attempt.</p>
 *
 * <p>{@link #send} blocks for as long as that takes, so never call it on a UI
 * thread.</p>
 */
public final class LauncherReportChannel {

    /** Environment variable that moves the directory, for tests and a non-default launcher. */
    public static final String DIR_ENV = "BWU_REPORT_DIR";
    /** The directory's place under the user's home. */
    public static final String DEFAULT_DIR = ".botwithus/reports";

    static final String REQUEST = ".rptreq";
    static final String REQUEST_TMP = ".rptreq.tmp";
    static final String WORKING = ".rptwork";
    static final String REPLY = ".rpt";

    private static final Logger log = LoggerFactory.getLogger(LauncherReportChannel.class);
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int RUN_ID_PREFIX = 8;
    private static final int NONCE_BYTES = 2;
    private static final String NO_RUN = "norun";

    private final Path dir;
    private final Timing timing;
    private final SecureRandom random = new SecureRandom();

    /**
     * How long each wait lasts and how often it looks.
     *
     * @param claimWindow how long a launcher has to claim a request
     * @param replyWindow how long a claimed request may take to answer
     * @param poll        how often the directory is looked at
     */
    public record Timing(Duration claimWindow, Duration replyWindow, Duration poll) {

        /** The waits the channel's contract fixes: 10 s to claim, 5 min to answer. */
        public static final Timing CONTRACT =
                new Timing(Duration.ofSeconds(10), Duration.ofMinutes(5), Duration.ofMillis(100));

        public Timing {
            Objects.requireNonNull(claimWindow, "claimWindow");
            Objects.requireNonNull(replyWindow, "replyWindow");
            Objects.requireNonNull(poll, "poll");
        }
    }

    /** A channel through {@code dir} with the contract's waits. */
    public LauncherReportChannel(Path dir) {
        this(dir, Timing.CONTRACT);
    }

    /** A channel with other waits; tests shorten them. */
    public LauncherReportChannel(Path dir, Timing timing) {
        this.dir = Objects.requireNonNull(dir, "dir");
        this.timing = Objects.requireNonNull(timing, "timing");
    }

    /** {@value #DIR_ENV} when set and not blank, else {@value #DEFAULT_DIR} under {@code home}. */
    public static Path directory(Map<String, String> env, Path home) {
        String override = env.get(DIR_ENV);
        if (override != null && !override.isBlank()) {
            return Path.of(override.strip());
        }
        return home.resolve(DEFAULT_DIR);
    }

    /** The channel this process should use: its environment and its user's home. */
    public static LauncherReportChannel forThisProcess() {
        return new LauncherReportChannel(directory(System.getenv(), Path.of(System.getProperty("user.home"))));
    }

    /** The directory requests are written to. */
    public Path directory() {
        return dir;
    }

    /**
     * A fresh request id: {@code <host pid>-<first 8 of the run id>-<4 hex>}. The
     * random tail keeps a second report of the same run from meeting the first's
     * files.
     */
    public String newRequestId(long hostPid, Optional<String> runId) {
        String run = runId.filter(r -> r.length() >= RUN_ID_PREFIX && ID.matcher(r).matches())
                .map(r -> r.substring(0, RUN_ID_PREFIX)).orElse(NO_RUN);
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        return hostPid + "-" + run + "-" + HexFormat.of().formatHex(nonce);
    }

    /**
     * Sends {@code request} under {@code id} and waits for the launcher's answer.
     * Never throws for anything the launcher or the disk does: every outcome is a
     * {@link ReportReply} with a message to show.
     *
     * @throws InterruptedException when the calling thread is interrupted; an
     *                              unclaimed request is taken back first
     */
    public ReportReply send(String id, ReportRequest request) throws InterruptedException {
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("request id must match " + ID.pattern() + ": " + id);
        }
        if (!Files.isDirectory(dir)) {
            // The launcher creates the directory, so without it there is no launcher.
            return ReportReply.Failed.launcherNotRunning();
        }
        if (!writeRequest(id, request)) {
            return ReportReply.Failed.badReply();
        }
        try {
            if (!awaitClaim(id) && withdraw(id)) {
                return ReportReply.Failed.launcherNotRunning();
            }
        } catch (InterruptedException e) {
            withdraw(id);
            throw e;
        }
        return awaitReply(id);
    }

    /** Writes the request beside its final name, then renames it into place. */
    private boolean writeRequest(String id, ReportRequest request) {
        Path tmp = file(id, REQUEST_TMP);
        try {
            Files.writeString(tmp, request.toJson(), StandardCharsets.UTF_8);
            Files.move(tmp, file(id, REQUEST), StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            log.warn("Could not write report request {} to {}: {}", id, dir, e.toString());
            deleteQuietly(tmp);
            return false;
        }
    }

    /** Whether a launcher claimed the request (or already answered it) within the window. */
    private boolean awaitClaim(String id) throws InterruptedException {
        long deadline = System.nanoTime() + timing.claimWindow().toNanos();
        do {
            if (Files.exists(file(id, WORKING)) || Files.exists(file(id, REPLY))) {
                return true;
            }
            Thread.sleep(timing.poll());
        } while (System.nanoTime() - deadline < 0);
        return false;
    }

    /**
     * Takes an unclaimed request back. Returns {@code false} when it was already
     * gone: a launcher claimed it in the instant the window closed, so the
     * request is in flight after all.
     */
    private boolean withdraw(String id) {
        try {
            return Files.deleteIfExists(file(id, REQUEST));
        } catch (IOException e) {
            log.warn("Could not take back report request {}: {}", id, e.toString());
            return true;
        }
    }

    /** Waits for the answer, reads it and deletes it. */
    private ReportReply awaitReply(String id) throws InterruptedException {
        Path reply = file(id, REPLY);
        long deadline = System.nanoTime() + timing.replyWindow().toNanos();
        do {
            if (Files.exists(reply)) {
                Optional<ReportReply> read = read(reply);
                if (read.isPresent()) {
                    deleteQuietly(reply);
                    return read.get();
                }
            }
            Thread.sleep(timing.poll());
        } while (System.nanoTime() - deadline < 0);
        log.warn("Report {} was claimed but not answered within {}", id, timing.replyWindow());
        return ReportReply.Failed.launcherTimeout();
    }

    /** The parsed answer, or empty when the file could not be opened yet (try again). */
    private static Optional<ReportReply> read(Path reply) {
        try {
            return Optional.of(ReportReply.parse(Files.readString(reply, StandardCharsets.UTF_8)));
        } catch (CharacterCodingException e) {
            return Optional.of(ReportReply.Failed.badReply());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Path file(String id, String suffix) {
        return dir.resolve(id + suffix);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("Could not delete {}: {}", path, e.toString());
        }
    }
}
