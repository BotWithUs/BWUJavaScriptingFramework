package com.botwithus.bot.core.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The host's failed-load list, across load passes and across both script
 * folders.
 *
 * <p>A single {@link LoadReport} only says what the latest pass saw. This keeps
 * the last failure of every JAR until that JAR loads cleanly, and drops it
 * when the JAR is deleted, so a failure stays visible however many reloads
 * later someone looks. Alongside the failures it lists, per folder, the JARs
 * shadowed by a newer JAR declaring the same script name
 * ({@link LoadIssue.DuplicateName}); those are recomputed on every pass.</p>
 *
 * <p>Fed by whoever runs a load pass ({@link #record}); read by the pages that
 * show failed loads. Thread-safe: passes run on worker threads while the GUI
 * reads every frame.</p>
 */
public final class LoadIssues {

    private static final Comparator<JarLoadOutcome> NEWEST_FIRST = Comparator
            .comparing((JarLoadOutcome o) -> o.lastModified().orElse(Instant.MIN), Comparator.reverseOrder())
            .thenComparing(o -> o.jar().getFileName().toString());

    private static final Comparator<LoadIssue> DISPLAY_ORDER = Comparator
            .comparing(LoadIssue::folder)
            .thenComparing(i -> i.jar().getFileName().toString());

    private final Clock clock;
    // Both guarded by this.
    private final Map<Path, LoadIssue.Failed> failures = new LinkedHashMap<>();
    private final Map<ScriptFolder, List<LoadIssue.DuplicateName>> duplicates =
            new EnumMap<>(ScriptFolder.class);

    public LoadIssues() {
        this(Clock.systemUTC());
    }

    /** @param clock stamps {@link LoadIssue.Failed#recordedAt()} */
    public LoadIssues(Clock clock) {
        this.clock = clock;
    }

    /**
     * Folds one load pass over {@code folder} into the list: each JAR that
     * failed replaces its previous entry, each JAR that loaded cleanly clears
     * its entry, entries whose JAR no longer exists are dropped, and the
     * folder's duplicate-name warnings are recomputed from this pass.
     */
    public synchronized void record(ScriptFolder folder, List<? extends JarLoadOutcome> outcomes) {
        Instant now = clock.instant();
        Map<Path, List<JarLoadOutcome>> byJar = outcomes.stream()
                .collect(Collectors.groupingBy(o -> keyOf(o.jar()), LinkedHashMap::new, Collectors.toList()));
        byJar.forEach((key, jarOutcomes) -> applyJar(folder, key, jarOutcomes, now));
        failures.values().removeIf(f -> f.folder() == folder && !Files.exists(f.jar()));
        duplicates.put(folder, findDuplicates(folder, outcomes));
    }

    /** Every entry: failures first, then duplicate-name warnings, each ordered by folder and JAR name. */
    public synchronized List<LoadIssue> issues() {
        List<LoadIssue> all = new ArrayList<>(failures.values().stream().sorted(DISPLAY_ORDER).toList());
        for (ScriptFolder folder : ScriptFolder.values()) {
            all.addAll(duplicates.getOrDefault(folder, List.of()));
        }
        return List.copyOf(all);
    }

    /** The entries for one folder, in {@link #issues()} order. */
    public List<LoadIssue> issues(ScriptFolder folder) {
        return issues().stream().filter(i -> i.folder() == folder).toList();
    }

    /** The recorded failure for {@code jar}, if its last pass failed. */
    public synchronized Optional<LoadIssue.Failed> failureOf(Path jar) {
        return Optional.ofNullable(failures.get(keyOf(jar)));
    }

    private void applyJar(ScriptFolder folder, Path key, List<JarLoadOutcome> jarOutcomes, Instant now) {
        Optional<JarLoadOutcome> failed = jarOutcomes.stream()
                .filter(o -> o.error().isPresent())
                .reduce((first, second) -> second);
        if (failed.isEmpty()) {
            failures.remove(key);
            return;
        }
        JarLoadOutcome f = failed.get();
        failures.put(key, new LoadIssue.Failed(folder, f.jar(), f.error().orElseThrow(), f.lastModified(), now));
    }

    private static List<LoadIssue.DuplicateName> findDuplicates(ScriptFolder folder,
                                                               List<? extends JarLoadOutcome> outcomes) {
        Map<String, List<JarLoadOutcome>> byName = new LinkedHashMap<>();
        for (JarLoadOutcome o : outcomes) {
            o.scriptName().ifPresent(name -> byName.computeIfAbsent(name, n -> new ArrayList<>()).add(o));
        }
        List<LoadIssue.DuplicateName> found = new ArrayList<>();
        byName.forEach((name, sameName) -> found.addAll(olderCopies(folder, name, sameName)));
        return List.copyOf(found);
    }

    /** One warning per JAR older than the newest JAR declaring {@code name}; none when only one JAR does. */
    private static List<LoadIssue.DuplicateName> olderCopies(ScriptFolder folder, String name,
                                                            List<JarLoadOutcome> sameName) {
        List<JarLoadOutcome> distinctJars = sameName.stream()
                .collect(Collectors.toMap(o -> keyOf(o.jar()), o -> o, (a, b) -> a, LinkedHashMap::new))
                .values().stream()
                .sorted(NEWEST_FIRST)
                .toList();
        if (distinctJars.size() < 2) {
            return List.of();
        }
        Path newest = distinctJars.getFirst().jar();
        return distinctJars.stream().skip(1)
                .map(o -> new LoadIssue.DuplicateName(folder, o.jar(), name, newest, o.lastModified()))
                .toList();
    }

    private static Path keyOf(Path jar) {
        return jar.toAbsolutePath().normalize();
    }
}
