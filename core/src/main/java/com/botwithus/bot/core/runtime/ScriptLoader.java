package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.core.crypto.SdnLoader;

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
 * Discovers BotScript implementations from JAR files in a scripts directory.
 * Each JAR is a Java module that {@code provides BotScript with ...}.
 * Loaded into a child ModuleLayer so ServiceLoader can find them.
 */
public final class ScriptLoader {

    private static final String SCRIPTS_DIR_NAME = "scripts";
    private static final String SCRIPTS_DIR_PROPERTY = "botwithus.scripts.dir";

    private ScriptLoader() {}

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
    private static Path resolveScriptsDir() {
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
            System.out.println("[ScriptLoader] Scripts directory not found: " + scriptsDir.toAbsolutePath());
            System.out.println("[ScriptLoader] Creating it — drop script JARs there and restart.");
            try {
                Files.createDirectories(scriptsDir);
            } catch (IOException e) {
                System.err.println("[ScriptLoader] Failed to create scripts directory: " + e.getMessage());
            }
            return List.of();
        }

        // Find all JARs in the scripts directory
        List<Path> jars;
        try (var stream = Files.list(scriptsDir)) {
            jars = stream.filter(p -> p.toString().endsWith(".jar")).toList();
        } catch (IOException e) {
            System.err.println("[ScriptLoader] Failed to scan scripts directory: " + e.getMessage());
            return List.of();
        }

        if (jars.isEmpty()) {
            System.out.println("[ScriptLoader] No JARs found in " + scriptsDir.toAbsolutePath());
            return List.of();
        }

        System.out.println("[ScriptLoader] Found " + jars.size() + " JAR(s) in " + scriptsDir.toAbsolutePath());

        ModuleFinder finder = ModuleFinder.of(scriptsDir);
        Set<ModuleReference> moduleReferences = finder.findAll();

        if (moduleReferences.isEmpty()) {
            System.out.println("[ScriptLoader] No modules found in JARs. "
                    + "Ensure each JAR has a module-info with 'provides BotScript with ...'");
            return List.of();
        }

        List<BotScript> allScripts = new ArrayList<>();
        ModuleLayer bootLayer = ModuleLayer.boot();

        for (ModuleReference ref : moduleReferences) {
            String name = ref.descriptor().name();
            var location = ref.location();
            if (location.isEmpty()) {
                System.out.println("[ScriptLoader] Module " + name + " has no location, skipping.");
                continue;
            }

            try {
                URL jarURL = location.get().toURL();
                Configuration cfg = bootLayer.configuration().resolve(
                        finder, ModuleFinder.of(), Collections.singleton(name));
                ModuleLayer layer = bootLayer.defineModulesWithOneLoader(
                        cfg, new URLClassLoader(new URL[]{jarURL}));

                Optional<Module> module = layer.findModule(name);
                if (module.isEmpty()) {
                    System.out.println("[ScriptLoader] Module " + name + " could not be found in layer, skipping.");
                    continue;
                }

                ServiceLoader<BotScript> loader = ServiceLoader.load(layer, BotScript.class);
                for (BotScript script : loader) {
                    allScripts.add(script);
                    System.out.println("[ScriptLoader] Loaded: " + script.getClass().getName());
                }
            } catch (Exception e) {
                System.err.println("[ScriptLoader] Failed to load module " + name + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        if (!allScripts.isEmpty()) {
            try {
                SdnLoader.lockdown();
                System.out.println("[ScriptLoader] Process lockdown enforced — unsigned DLL loading blocked.");
            } catch (Exception e) {
                System.err.println("[ScriptLoader] lockdown0() failed: " + e.getMessage());
            }
        }

        return allScripts;
    }
}
