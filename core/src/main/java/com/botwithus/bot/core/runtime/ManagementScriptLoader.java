package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.script.ManagementScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Discovers {@link ManagementScript} implementations from JAR files
 * in the {@code scripts/management/} directory.
 *
 * <p>Each JAR must be a Java module declaring
 * {@code provides com.botwithus.bot.api.script.ManagementScript with <ClassName>}.
 *
 * <p>As with {@link LocalScriptLoader}, JARs are loaded from private copies
 * taken by {@link ScriptJarStaging} so the management directory stays writable
 * while the host is running.</p>
 */
public final class ManagementScriptLoader {

    private static final Logger log = LoggerFactory.getLogger(ManagementScriptLoader.class);
    private static final String MANAGEMENT_DIR = "management";
    private static final PreviousLoaderTracker previousLoaders = new PreviousLoaderTracker();
    private static final ScriptJarStaging staging = new ScriptJarStaging(MANAGEMENT_DIR);

    /**
     * Pins the classloader that defined {@code script} so no later reload closes
     * it. See {@link LocalScriptLoader#pinLoaderOf} — same contract, separate
     * tracker, because each loader owns its own.
     */
    static void pinLoaderOf(ManagementScript script) {
        if (script != null) {
            previousLoaders.pin(script.getClass().getClassLoader());
        }
    }

    private ManagementScriptLoader() {}

    /**
     * Loads all ManagementScript providers from the default
     * {@code scripts/management/} directory. Failures are dropped — use
     * {@link #loadReport()} to see them.
     */
    public static List<ManagementScript> loadScripts() {
        return loadReport().scripts();
    }

    /**
     * Loads all ManagementScript providers from JARs in the given directory.
     * Failures are dropped — use {@link #loadReport(Path)} to see them.
     */
    public static List<ManagementScript> loadScripts(Path managementDir) {
        return loadReport(managementDir).scripts();
    }

    /**
     * The folder inside {@code scriptsDir} that management scripts load from.
     * Public so the GUI names the folder this loader reads rather than a copy
     * of its name.
     */
    public static Path managementDirIn(Path scriptsDir) {
        return scriptsDir.resolve(MANAGEMENT_DIR);
    }

    /**
     * Loads the default {@code scripts/management/} directory and reports
     * every JAR: the scripts that loaded and the JARs that did not, with why.
     */
    public static ManagementLoadReport loadReport() {
        return loadReport(managementDirIn(LocalScriptLoader.resolveScriptsDir()));
    }

    /** {@link #loadReport()} over an explicit directory. */
    public static ManagementLoadReport loadReport(Path managementDir) {
        return loadReport(managementDir, staging);
    }

    /**
     * {@link #loadReport(Path)} through an explicit staging area. Package-private
     * so tests stage under a temporary root rather than the real
     * {@code ~/.botwithus/staged-scripts} a running host shares.
     */
    static ManagementLoadReport loadReport(Path managementDir, ScriptJarStaging staging) {
        if (!Files.isDirectory(managementDir)) {
            createDirectoryIfMissing(managementDir);
            return ManagementLoadReport.EMPTY;
        }

        previousLoaders.closeAll();

        List<Path> jars = listJars(managementDir);
        if (jars.isEmpty()) {
            log.info("No JARs in {}", managementDir.toAbsolutePath());
            return ManagementLoadReport.EMPTY;
        }
        log.info("Found {} JAR(s) in {}", jars.size(), managementDir.toAbsolutePath());

        ScriptJarStaging.Staged staged = staging.stage(jars, managementDir);
        StagedModules.Probe probe = StagedModules.probe(staged, jars);
        List<ManagementLoadResult> results = new ArrayList<>();
        probe.rejected().forEach((jar, error) -> {
            log.warn("Not loading {}: {}", jar.getFileName(), error.getMessage());
            results.add(ManagementLoadResult.failure(jar, error));
        });
        ModuleLayer bootLayer = ModuleLayer.boot();
        for (ModuleReference ref : probe.modules()) {
            results.addAll(loadModuleScripts(ref, probe.finder(), bootLayer, staged));
        }
        return new ManagementLoadReport(results);
    }

    private static void createDirectoryIfMissing(Path managementDir) {
        try {
            Files.createDirectories(managementDir);
            log.info("Created: {}", managementDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to create directory: {}", e.getMessage());
        }
    }

    private static List<Path> listJars(Path managementDir) {
        try (var stream = Files.list(managementDir)) {
            return stream.filter(p -> p.toString().endsWith(".jar")).toList();
        } catch (IOException e) {
            log.error("Failed to scan directory: {}", e.getMessage());
            return List.of();
        }
    }

    private static List<ManagementLoadResult> loadModuleScripts(ModuleReference ref, ModuleFinder finder,
                                                                ModuleLayer bootLayer,
                                                                ScriptJarStaging.Staged staged) {
        String name = ref.descriptor().name();
        Optional<URI> location = ref.location();
        if (location.isEmpty()) {
            return List.of(ManagementLoadResult.failure(Path.of(name),
                    new IllegalStateException("Module " + name + " has no resolvable location")));
        }
        Path jar = staged.sourceOf(Path.of(location.get()));
        try {
            URL jarURL = location.get().toURL();
            Configuration cfg = bootLayer.configuration().resolve(
                    finder, ModuleFinder.of(), Collections.singleton(name));
            URLClassLoader classLoader = new URLClassLoader(new URL[]{jarURL});
            previousLoaders.add(classLoader);
            ModuleLayer layer = bootLayer.defineModulesWithOneLoader(cfg, classLoader);

            List<ManagementLoadResult> results = new ArrayList<>();
            for (ManagementScript script : ServiceLoader.load(layer, ManagementScript.class)) {
                log.info("Loaded: {}", script.getClass().getName());
                results.add(ManagementLoadResult.success(jar, script));
            }
            if (results.isEmpty()) {
                return List.of(ManagementLoadResult.failure(jar, new IllegalStateException(
                        "Module '" + name + "' contains no ManagementScript providers")));
            }
            return results;
        } catch (Exception e) {
            log.error("Failed to load module {}: {}", name, e.getMessage());
            return List.of(ManagementLoadResult.failure(jar, e));
        }
    }
}
