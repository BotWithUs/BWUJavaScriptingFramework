package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;

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
            System.out.println("[LocalScriptLoader] Scripts directory not found: " + scriptsDir.toAbsolutePath());
            System.out.println("[LocalScriptLoader] Creating it — drop script JARs there and restart.");
            try {
                Files.createDirectories(scriptsDir);
            } catch (IOException e) {
                System.err.println("[LocalScriptLoader] Failed to create scripts directory: " + e.getMessage());
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
            System.err.println("[LocalScriptLoader] Failed to scan scripts directory: " + e.getMessage());
            return List.of();
        }

        if (jars.isEmpty()) {
            System.out.println("[LocalScriptLoader] No JARs found in " + scriptsDir.toAbsolutePath());
            return List.of();
        }

        System.out.println("[LocalScriptLoader] Found " + jars.size() + " JAR(s) in " + scriptsDir.toAbsolutePath());

        ModuleFinder finder = ModuleFinder.of(scriptsDir);
        Set<ModuleReference> moduleReferences = finder.findAll();

        if (moduleReferences.isEmpty()) {
            System.out.println("[LocalScriptLoader] No modules found in JARs. "
                    + "Ensure each JAR has a module-info with 'provides BotScript with ...'");
            return List.of();
        }

        // Fail-fast: if this module (core) doesn't declare 'uses BotScript',
        // ServiceLoader will silently return empty for ALL script JARs.
        Module coreModule = LocalScriptLoader.class.getModule();
        if (coreModule.isNamed()) {
            boolean declaresUses = coreModule.getDescriptor().uses()
                    .contains(BotScript.class.getName());
            if (!declaresUses) {
                System.err.println("[LocalScriptLoader] FATAL: Module '" + coreModule.getName()
                        + "' is missing 'uses com.botwithus.bot.api.BotScript;' in module-info.java");
                System.err.println("[LocalScriptLoader] ServiceLoader will not discover any scripts without it!");
                return List.of();
            }
        }

        List<BotScript> allScripts = new ArrayList<>();
        ModuleLayer bootLayer = ModuleLayer.boot();

        for (ModuleReference ref : moduleReferences) {
            String name = ref.descriptor().name();
            var location = ref.location();
            if (location.isEmpty()) {
                System.out.println("[LocalScriptLoader] Module " + name + " has no location, skipping.");
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
                    System.out.println("[LocalScriptLoader] Module " + name + " could not be found in layer, skipping.");
                    continue;
                }

                // Check if the module declares 'provides BotScript'
                boolean providesBotScript = module.get().getDescriptor().provides().stream()
                        .anyMatch(p -> p.service().equals(BotScript.class.getName()));

                ServiceLoader<BotScript> loader = ServiceLoader.load(layer, BotScript.class);
                int countBefore = allScripts.size();
                for (BotScript script : loader) {
                    allScripts.add(script);
                    System.out.println("[LocalScriptLoader] Loaded: " + script.getClass().getName());
                }

                int loaded = allScripts.size() - countBefore;
                if (loaded == 0 && providesBotScript) {
                    System.err.println("[LocalScriptLoader] WARNING: Module '" + name
                            + "' declares 'provides BotScript' but ServiceLoader found 0 implementations.");
                } else if (loaded == 0) {
                    System.out.println("[LocalScriptLoader] Module '" + name
                            + "' contains no BotScript providers — skipping.");
                }
            } catch (Exception e) {
                System.err.println("[LocalScriptLoader] Failed to load module " + name + ": " + e.getMessage());
                e.printStackTrace();
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
                System.err.println("[LocalScriptLoader] Failed to close previous classloader: " + e.getMessage());
            }
        }
        previousLoaders.clear();
    }
}
