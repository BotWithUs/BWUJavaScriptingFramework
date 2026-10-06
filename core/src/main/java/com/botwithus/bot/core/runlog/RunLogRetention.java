package com.botwithus.bot.core.runlog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Keeps the run-log tree inside the spec's bounds, enforced when a run starts:
 * at most {@value #MAX_FILES_PER_SCRIPT} files per script directory (counting
 * the one about to be created) and at most {@value #MAX_TREE_BYTES} bytes across
 * every script directory, oldest files deleted first.
 *
 * <p>A file another run still has open is never deleted, and a delete that fails
 * (another host process holds the file) is skipped rather than retried: the next
 * run start gets another chance.</p>
 */
final class RunLogRetention {

    static final int MAX_FILES_PER_SCRIPT = 20;
    static final long MAX_TREE_BYTES = 200L * 1024 * 1024;
    static final String EXTENSION = ".log";

    private static final Logger log = LoggerFactory.getLogger(RunLogRetention.class);
    private static final Comparator<LogFile> NEWEST_FIRST = Comparator
            .comparing(LogFile::modified).reversed()
            .thenComparing(LogFile::path, Comparator.reverseOrder());

    private final long maxTreeBytes;
    private final int maxFilesPerScript;

    RunLogRetention() {
        this(MAX_FILES_PER_SCRIPT, MAX_TREE_BYTES);
    }

    /** Smaller bounds, for tests that cannot write 200 MB. */
    RunLogRetention(int maxFilesPerScript, long maxTreeBytes) {
        this.maxFilesPerScript = maxFilesPerScript;
        this.maxTreeBytes = maxTreeBytes;
    }

    /**
     * Makes room for one new file in {@code scriptDir} under {@code root}, never
     * touching a path in {@code open}.
     */
    void beforeNewFile(Path root, Path scriptDir, Set<Path> open) {
        List<LogFile> inScript = listLogs(scriptDir);
        inScript.sort(NEWEST_FIRST);
        // One slot is for the file this run is about to create.
        for (int i = maxFilesPerScript - 1; i < inScript.size(); i++) {
            delete(inScript.get(i), open);
        }
        capTree(root, open);
    }

    private void capTree(Path root, Set<Path> open) {
        List<LogFile> all = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).forEach(d -> all.addAll(listLogs(d)));
        } catch (IOException e) {
            return;
        }
        long total = all.stream().mapToLong(LogFile::size).sum();
        all.sort(NEWEST_FIRST.reversed());
        for (LogFile file : all) {
            if (total <= maxTreeBytes) {
                return;
            }
            if (delete(file, open)) {
                total -= file.size();
            }
        }
    }

    private static List<LogFile> listLogs(Path dir) {
        List<LogFile> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(p -> p.getFileName().toString().endsWith(EXTENSION))
                    .filter(Files::isRegularFile)
                    .forEach(p -> describe(p, files));
        } catch (IOException e) {
            log.debug("Could not list run logs in {}: {}", dir, e.toString());
        }
        return files;
    }

    private static void describe(Path path, List<LogFile> into) {
        try {
            into.add(new LogFile(path, Files.getLastModifiedTime(path), Files.size(path)));
        } catch (IOException e) {
            // Gone between the listing and now; nothing to retain or delete.
        }
    }

    private static boolean delete(LogFile file, Set<Path> open) {
        if (open.contains(file.path().toAbsolutePath().normalize())) {
            return false;
        }
        try {
            return Files.deleteIfExists(file.path());
        } catch (IOException e) {
            log.debug("Could not delete old run log {}: {}", file.path().getFileName(), e.toString());
            return false;
        }
    }

    private record LogFile(Path path, FileTime modified, long size) {
    }
}
