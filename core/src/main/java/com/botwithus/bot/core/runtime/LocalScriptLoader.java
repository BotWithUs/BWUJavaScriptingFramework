package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Discovers BotScript implementations from local JAR files in a scripts directory.
 * Each JAR is a Java module that {@code provides BotScript with ...}.
 * Loaded into a child ModuleLayer so ServiceLoader can find them.
 */
public final class LocalScriptLoader {

    private static final Logger log = LoggerFactory.getLogger(LocalScriptLoader.class);
    private static final String SCRIPTS_DIR_NAME = "scripts";
    private static final String SCRIPTS_DIR_PROPERTY = "botwithus.scripts.dir";

    /** Track classloaders from previous loads so we can close them on reload. */
    private static final List<URLClassLoader> previousLoaders = new ArrayList<>();

    private LocalScriptLoader() {}

    /**
     * Loads all BotScript providers from JARs in the default {@code scripts/} directory.
     */
    public static List<BotScript> loadScripts() {
        return loadScripts(resolveScriptsDir());
    }

    /**
     * Resolves the scripts directory. Checks (in order):
     * 1. System property {@code botwithus.scripts.dir}
     * 2. {@code scripts/} relative to the user home {@code .botwithus/} directory
     * 3. {@code scripts/} relative to the working directory
     */
    static Path resolveScriptsDir() {
        String override = System.getProperty(SCRIPTS_DIR_PROPERTY);
        if (override != null) {
            return Path.of(override);
        }
        // Walk up from working directory to find scripts/ (handles submodule working dirs)
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 3; i++) {
            Path candidate = dir.resolve(SCRIPTS_DIR_NAME);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
            if (dir == null) break;
        }
        // Fallback: create in working directory
        return Path.of(SCRIPTS_DIR_NAME);
    }

    /**
     * Loads all BotScript providers from JARs in the given directory.
     */
    public static List<BotScript> loadScripts(Path scriptsDir) {
        if (!Files.isDirectory(scriptsDir)) {
            log.info("Scripts directory not found: {}", scriptsDir.toAbsolutePath());
            log.info("Creating it — drop script JARs there and restart.");
            try {
                Files.createDirectories(scriptsDir);
            } catch (IOException e) {
                log.error("Failed to create scripts directory: {}", e.getMessage());
            }
            return List.of();
        }

        // Close previous classloaders to release JAR file handles (critical on Windows)
        closePreviousLoaders();

        // Find all JARs in the scripts directory
        List<Path> jars;
        try (var stream = Files.list(scriptsDir)) {
            jars = stream.filter(p -> p.toString().endsWith(".jar")).toList();
        } catch (IOException e) {
            log.error("Failed to scan scripts directory: {}", e.getMessage());
            return List.of();
        }

        if (jars.isEmpty()) {
            log.info("No JARs found in {}", scriptsDir.toAbsolutePath());
            return List.of();
        }

        log.info("Found {} JAR(s) in {}", jars.size(), scriptsDir.toAbsolutePath());

        ModuleFinder finder = ModuleFinder.of(scriptsDir);
        Set<ModuleReference> moduleReferences = finder.findAll();

        if (moduleReferences.isEmpty()) {
            log.info("No modules found in JARs. Ensure each JAR has a module-info with 'provides BotScript with ...'");
            return List.of();
        }

        // Fail-fast: if this module (core) doesn't declare 'uses BotScript',
        // ServiceLoader will silently return empty for ALL script JARs.
        Module coreModule = LocalScriptLoader.class.getModule();
        if (coreModule.isNamed()) {
            boolean declaresUses = coreModule.getDescriptor().uses()
                    .contains(BotScript.class.getName());
            if (!declaresUses) {
                log.error("FATAL: Module '{}' is missing 'uses com.botwithus.bot.api.BotScript;' in module-info.java", coreModule.getName());
                log.error("ServiceLoader will not discover any scripts without it!");
                return List.of();
            }
        }

        List<BotScript> allScripts = new ArrayList<>();
        ModuleLayer bootLayer = ModuleLayer.boot();

        for (ModuleReference ref : moduleReferences) {
            String name = ref.descriptor().name();
            var location = ref.location();
            if (location.isEmpty()) {
                log.info("Module {} has no location, skipping.", name);
                continue;
            }

            try {
                URL jarURL = location.get().toURL();
                Configuration cfg = bootLayer.configuration().resolve(
                        finder, ModuleFinder.of(), Collections.singleton(name));
                URLClassLoader classLoader = new URLClassLoader(new URL[]{jarURL});
                previousLoaders.add(classLoader);
                ModuleLayer layer = bootLayer.defineModulesWithOneLoader(
                        cfg, classLoader);

                Optional<Module> module = layer.findModule(name);
                if (module.isEmpty()) {
                    log.info("Module {} could not be found in layer, skipping.", name);
                    continue;
                }

                // Check if the module declares 'provides BotScript'
                boolean providesBotScript = module.get().getDescriptor().provides().stream()
                        .anyMatch(p -> p.service().equals(BotScript.class.getName()));

                ServiceLoader<BotScript> loader = ServiceLoader.load(layer, BotScript.class);
                int countBefore = allScripts.size();
                for (BotScript script : loader) {
                    allScripts.add(script);
                    log.info("Loaded: {}", script.getClass().getName());
                }

                int loaded = allScripts.size() - countBefore;
                if (loaded == 0 && providesBotScript) {
                    log.warn("Module '{}' declares 'provides BotScript' but ServiceLoader found 0 implementations.", name);
                } else if (loaded == 0) {
                    log.info("Module '{}' contains no BotScript providers — skipping.", name);
                }
            } catch (Exception e) {
                log.error("Failed to load module {}: {}", name, e.getMessage(), e);
            }
        }

        return allScripts;
    }

    /**
     * Closes all classloaders from previous loads, releasing JAR file handles.
     * This is necessary on Windows where open file handles prevent re-reading JARs.
     */
    private static void closePreviousLoaders() {
        for (URLClassLoader loader : previousLoaders) {
            try {
                loader.close();
            } catch (IOException e) {
                log.error("Failed to close previous classloader: {}", e.getMessage());
            }
        }
        previousLoaders.clear();
    }
}
