package com.botwithus.bot.core.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Copies script JARs into a private staging directory so the host never opens
 * a handle on the directory a scripter rebuilds into.
 *
 * <p>This exists because a loaded script JAR cannot be released. Defining a
 * child {@code ModuleLayer} over a JAR makes the layer's internal
 * {@code jdk.internal.loader.Loader} open a {@code ModuleReader} — a
 * {@code JarFile} — on the first class it loads, and that reader has no public
 * close. Closing the {@link java.net.URLClassLoader} handed to
 * {@code defineModulesWithOneLoader} does not help: it is only the layer's
 * <em>parent</em>, and never opened the JAR. The handle is released solely by
 * GC, once the layer, its loader, its classes and every instance of them are
 * unreachable — so any lingering reference (a quarantined runner, a pinned
 * loader) keeps the file open for the life of the process.</p>
 *
 * <p>On Windows an open JAR handle denies delete, which is exactly what
 * Gradle's {@code Copy} does when it installs a rebuilt JAR. Loading a copy
 * instead moves that undeletable handle onto a throwaway file, leaving the
 * scripts directory writable at all times: rebuild, watcher fires, reload
 * stages a fresh copy.</p>
 *
 * <p>Staging lives under {@code ~/.botwithus/}, alongside the scripts
 * directory the loader already treats as trusted, rather than in the shared
 * system temp directory — loaded JARs run as fully-trusted code, so the set of
 * places one can be read from must not widen.</p>
 */
final class ScriptJarStaging {

    private static final Logger log = LoggerFactory.getLogger(ScriptJarStaging.class);
    private static final String USER_CONFIG_DIR_NAME = ".botwithus";
    private static final String STAGING_DIR_NAME = "staged-scripts";

    private final String label;
    private final Path root;
    private final long pid;
    private int generation;

    /**
     * @param label distinguishes one loader's staging directories from
     *              another's within the shared root
     */
    ScriptJarStaging(String label) {
        this(label, Path.of(System.getProperty("user.home"), USER_CONFIG_DIR_NAME, STAGING_DIR_NAME));
    }

    /**
     * Staging under an explicit root. Package-private for
     * {@code ScriptJarStagingTest}, which must not sweep — or leave a leaked
     * JAR handle in — the real {@code ~/.botwithus/staged-scripts} shared with
     * a running host.
     */
    ScriptJarStaging(String label, Path root) {
        this.label = label;
        this.root = root;
        this.pid = ProcessHandle.current().pid();
    }

    /**
     * One staging pass: the directory to load from, how to map a loaded JAR
     * back to the file the user actually built, and the JARs that could not be
     * copied.
     *
     * @param dir              directory the module layer should be resolved against
     * @param sourceByFileName staged file name to the source JAR it was copied from
     * @param failures         source JAR to the error that stopped it being staged
     */
    record Staged(Path dir, Map<String, Path> sourceByFileName, Map<Path, IOException> failures) {

        /** Fallback for when no staging directory could be created: load in place. */
        static Staged inPlace(Path dir) {
            return new Staged(dir, Map.of(), Map.of());
        }

        /**
         * The path to report for {@code staged} — the file the scripter built,
         * so errors name a path they recognise. Returns the argument unchanged
         * when loading in place.
         */
        Path sourceOf(Path staged) {
            return sourceByFileName.getOrDefault(staged.getFileName().toString(), staged);
        }
    }

    /**
     * Copies every JAR in {@code jars} into a fresh generation directory.
     * Never throws: a JAR that cannot be copied is reported in
     * {@link Staged#failures()}, and a staging root that cannot be created
     * degrades to loading in place from {@code sourceDir}.
     */
    Staged stage(List<Path> jars, Path sourceDir) {
        Optional<Path> dir = nextGenerationDir();
        if (dir.isEmpty()) {
            log.warn("Staging unavailable; loading JARs in place from {}. "
                    + "Rebuilding a loaded script will fail until the host restarts.", sourceDir);
            return Staged.inPlace(sourceDir);
        }
        Map<String, Path> sources = new LinkedHashMap<>();
        Map<Path, IOException> failures = new LinkedHashMap<>();
        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            try {
                Files.copy(jar, dir.get().resolve(name), StandardCopyOption.REPLACE_EXISTING);
                sources.put(name, jar);
            } catch (IOException e) {
                log.error("Failed to stage {}: {}", name, e.getMessage());
                failures.put(jar, e);
            }
        }
        return new Staged(dir.get(), Map.copyOf(sources), Map.copyOf(failures));
    }

    /**
     * Creates the next generation directory, sweeping what can be swept first.
     * Empty when the staging root is unusable.
     */
    private Optional<Path> nextGenerationDir() {
        try {
            Files.createDirectories(root);
            sweep();
            generation++;
            Path dir = root.resolve(label + "-" + pid + "-" + generation);
            Files.createDirectories(dir);
            return Optional.of(dir);
        } catch (IOException e) {
            log.error("Could not create a staging directory under {}: {}", root, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Deletes staging directories left by processes that are no longer
     * running, plus this process's earlier generations.
     *
     * <p>Best-effort by design: a generation still owned by a live module
     * layer holds undeletable JARs, so its directory survives until a later
     * run sees this pid is gone. That is the whole point of staging — the
     * undeletable file is a throwaway copy, not the scripter's build output.</p>
     *
     * <p>Sweeping an earlier generation of this same process cannot pull a JAR
     * out from under a still-running script: a loaded module has an open module
     * reader, which denies the delete outright on Windows and keeps reading
     * through the unlinked file on POSIX.</p>
     */
    private void sweep() throws IOException {
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .filter(this::isSweepable)
                    .forEach(ScriptJarStaging::deleteQuietly);
        }
    }

    /** True for a dead process's directory, or one of our own earlier generations. */
    private boolean isSweepable(Path dir) {
        return ownerPid(dir)
                .map(owner -> owner == pid || ProcessHandle.of(owner).isEmpty())
                .orElse(false);
    }

    /** The pid encoded in a {@code <label>-<pid>-<generation>} directory name. */
    private static Optional<Long> ownerPid(Path dir) {
        String[] parts = dir.getFileName().toString().split("-");
        if (parts.length < 3) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(parts[parts.length - 2]));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> tree = Files.walk(dir)) {
            // Deepest first, so a directory is empty by the time it is removed.
            tree.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // Still held by a live layer's module reader. It will be
                    // swept by whichever run first sees this pid gone.
                    log.debug("Staging leftover not deletable yet: {}", path);
                }
            });
        } catch (IOException e) {
            log.debug("Could not sweep staging directory {}: {}", dir, e.getMessage());
        }
    }
}
