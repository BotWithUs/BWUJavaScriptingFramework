package com.botwithus.bot.core.runtime;

import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Sorts one staging pass into the modules a loader can define layers over and
 * the JARs it must report as failed, one JAR at a time.
 *
 * <p>Handing the whole staging directory to {@link ModuleFinder#of} fails the
 * entire pass on a single bad JAR: one unreadable file (a JAR caught
 * half-written by the folder watcher), or two JARs declaring the same module —
 * which is exactly what an older build left beside a newer one looks like.
 * Probing each JAR alone pins the failure on the JAR that caused it and lets
 * every other script load.</p>
 *
 * <p>JARs are taken newest first by the source file's modification time, so
 * when two declare the same module the newest is loaded and the older ones
 * are reported, and the loaders hand scripts to a runtime in that order.</p>
 */
final class StagedModules {

    private static final Comparator<Path> NEWEST_FIRST = Comparator
            .comparing((Path jar) -> JarLoadOutcome.readLastModified(jar).orElse(Instant.MIN),
                    Comparator.reverseOrder())
            .thenComparing(jar -> jar.getFileName().toString());

    private StagedModules() {}

    /**
     * @param finder   resolves every module in {@code modules}, and nothing else
     * @param modules  the modules to load, newest source JAR first
     * @param rejected source JAR to why it will not load, staging failures included
     */
    record Probe(ModuleFinder finder, List<ModuleReference> modules, Map<Path, Throwable> rejected) {
    }

    /** Probes every JAR in {@code sources} that {@code staged} managed to copy. */
    static Probe probe(ScriptJarStaging.Staged staged, List<Path> sources) {
        Map<Path, Throwable> rejected = new LinkedHashMap<>(staged.failures());
        Map<String, Path> winnerSourceByModule = new LinkedHashMap<>();
        Map<String, Path> winnerCopyByModule = new LinkedHashMap<>();
        List<Path> candidates = sources.stream()
                .filter(jar -> !staged.failures().containsKey(jar))
                .sorted(NEWEST_FIRST)
                .toList();
        for (Path source : candidates) {
            Path copy = staged.copyOf(source);
            Optional<String> module = moduleNameOf(copy, source, rejected);
            if (module.isEmpty()) {
                continue;
            }
            Path newer = winnerSourceByModule.putIfAbsent(module.get(), source);
            if (newer != null) {
                rejected.put(source, duplicateModule(module.get(), newer));
                continue;
            }
            winnerCopyByModule.put(module.get(), copy);
        }
        ModuleFinder finder = ModuleFinder.of(winnerCopyByModule.values().toArray(Path[]::new));
        List<ModuleReference> modules = winnerCopyByModule.keySet().stream()
                .flatMap(name -> finder.find(name).stream())
                .toList();
        return new Probe(finder, modules, Map.copyOf(rejected));
    }

    /** The module {@code copy} declares, or empty after recording why it has none against {@code source}. */
    private static Optional<String> moduleNameOf(Path copy, Path source, Map<Path, Throwable> rejected) {
        Set<ModuleReference> found;
        try {
            found = ModuleFinder.of(copy).findAll();
        } catch (FindException e) {
            rejected.put(source, e);
            return Optional.empty();
        }
        Optional<String> name = found.stream().map(ref -> ref.descriptor().name()).findFirst();
        if (name.isEmpty()) {
            rejected.put(source, new IllegalStateException(
                    "JAR is not a Java module — missing module-info.java with 'provides ...'"));
        }
        return name;
    }

    private static IllegalStateException duplicateModule(String module, Path newer) {
        return new IllegalStateException("Module '" + module + "' is also in the newer JAR "
                + newer.getFileName() + ", which was loaded instead. Delete this older copy.");
    }
}
