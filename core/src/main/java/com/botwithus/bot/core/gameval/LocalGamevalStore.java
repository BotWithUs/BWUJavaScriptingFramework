package com.botwithus.bot.core.gameval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.LongPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The directory of gameval indexes this host baked from the local game cache,
 * shared by every host on the machine ({@code ~/.botwithus/native/gameval-local/}).
 *
 * <p><b>Published files are immutable and named by version</b>
 * ({@code gameval-<index_version>.sqlite}). A build is written to a temp name
 * unique to its process and then renamed to a name that does not exist yet, so
 * publishing never has to replace a file — the one rename Windows refuses while
 * another host has that file open through sqlite. Any number of hosts can read
 * the same published file at once, and no host ever sees one half-written.</p>
 *
 * <p>The shipped {@code gameval.sqlite} beside this directory belongs to the
 * launcher's data.zip install and is never written here.</p>
 *
 * <p>A lock file stops two hosts building the same version at once. It is
 * taken without waiting and only around a build; readers never take it.</p>
 */
final class LocalGamevalStore {

    /** Directory name under the native cache root. */
    static final String DIR_NAME = "gameval-local";

    private static final Logger log = LoggerFactory.getLogger(LocalGamevalStore.class);

    private static final String PUBLISHED_PREFIX = "gameval-";
    private static final String PUBLISHED_SUFFIX = ".sqlite";
    private static final String BUILD_SUFFIX = ".build";
    private static final String LOCK_FILE_NAME = "build.lock";
    /** {@code gameval-<version>.sqlite}; versions may be negative. */
    private static final Pattern PUBLISHED =
            Pattern.compile(Pattern.quote(PUBLISHED_PREFIX) + "(-?\\d+)" + Pattern.quote(PUBLISHED_SUFFIX));
    /** {@code <pid>-<n>.build}, plus the {@code .partial} the library writes beside it. */
    private static final Pattern BUILD = Pattern.compile("(\\d+)-\\d+" + Pattern.quote(BUILD_SUFFIX) + ".*");

    private final Path dir;
    private final long pid;
    private final LongPredicate isProcessAlive;

    /**
     * @param dir            the store directory; created when a build needs it
     * @param pid            this process's id, stamped into build temp names
     * @param isProcessAlive whether a pid is still running, for the temp-file sweep
     */
    LocalGamevalStore(Path dir, long pid, LongPredicate isProcessAlive) {
        this.dir = Objects.requireNonNull(dir, "dir");
        this.pid = pid;
        this.isProcessAlive = Objects.requireNonNull(isProcessAlive, "isProcessAlive");
    }

    /** The published file for {@code version}, whether or not it exists. */
    Path publishedPath(int version) {
        return dir.resolve(PUBLISHED_PREFIX + version + PUBLISHED_SUFFIX);
    }

    /** The published file for {@code version}, when one exists. */
    Optional<Path> published(int version) {
        Path path = publishedPath(version);
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    /** A fresh temp path for one build by this process. Creates the directory. */
    Path newBuildPath() throws IOException {
        Files.createDirectories(dir);
        return dir.resolve(pid + "-" + System.nanoTime() + BUILD_SUFFIX);
    }

    /**
     * Renames a finished build to its published name. If that name already exists
     * another host published first; the copies are equivalent, so the temp file is
     * deleted and theirs is used.
     *
     * @return the published path
     */
    Path publish(Path built, int version) throws IOException {
        Path target = publishedPath(version);
        try {
            // No REPLACE_EXISTING and no ATOMIC_MOVE: on Windows ATOMIC_MOVE implies
            // replace. A plain same-directory move is a rename that fails if the
            // target exists, which is exactly the "someone else won" signal.
            Files.move(built, target);
        } catch (FileAlreadyExistsException e) {
            log.debug("gameval build {} lost the race to {}; using that one", built, target);
            deleteQuietly(built);
        }
        return target;
    }

    /**
     * Takes the build lock without waiting.
     *
     * @return the held lock, or empty when another host (or another opener in this
     *         process) is building
     */
    Optional<BuildLock> tryLockForBuild() throws IOException {
        Files.createDirectories(dir);
        FileChannel channel = FileChannel.open(dir.resolve(LOCK_FILE_NAME),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock != null) {
                return Optional.of(new BuildLock(channel));
            }
        } catch (OverlappingFileLockException e) {
            log.debug("gameval build lock already held in this process");
        }
        channel.close();
        return Optional.empty();
    }

    /**
     * Deletes published files of every version but {@code keepVersion}, and build
     * temp files whose process is gone. A delete that fails — on Windows, because
     * another host still has that file open — is left for a later start.
     */
    void sweep(int keepVersion) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                if (isSweepable(entry.getFileName().toString(), keepVersion)) {
                    deleteQuietly(entry);
                }
            }
        } catch (IOException e) {
            log.debug("could not sweep {}: {}", dir, e.getMessage());
        }
    }

    private boolean isSweepable(String name, int keepVersion) {
        Matcher published = PUBLISHED.matcher(name);
        if (published.matches()) {
            return !published.group(1).equals(Integer.toString(keepVersion));
        }
        OptionalLong owner = buildOwner(name);
        return owner.isPresent() && !isProcessAlive.test(owner.getAsLong());
    }

    private static OptionalLong buildOwner(String name) {
        Matcher build = BUILD.matcher(name);
        if (!build.matches()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(build.group(1)));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("could not delete {} (in use?): {}", path, e.getMessage());
        }
    }

    /** The held build lock; closing it releases the lock. */
    static final class BuildLock implements AutoCloseable {

        private final FileChannel channel;

        private BuildLock(FileChannel channel) {
            this.channel = channel;
        }

        @Override
        public void close() {
            try {
                channel.close();
            } catch (IOException e) {
                log.debug("closing the gameval build lock failed", e);
            }
        }
    }
}
