package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.script.ManagementScript;

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
 * Discovers {@link ManagementScript} implementations from JAR files
 * in the {@code scripts/management/} directory.
 *
 * <p>Each JAR must be a Java module declaring
 * {@code provides com.botwithus.bot.api.script.ManagementScript with <ClassName>}.
 */
public final class ManagementScriptLoader {

    private static final String MANAGEMENT_DIR = "management";
    private static final List<URLClassLoader> previousLoaders = new ArrayList<>();

    private ManagementScriptLoader() {}

    /**
     * Loads all ManagementScript providers from the default
     * {@code scripts/management/} directory.
     */
    public static List<ManagementScript> loadScripts() {
        Path scriptsDir = LocalScriptLoader.resolveScriptsDir();
        Path managementDir = scriptsDir.resolve(MANAGEMENT_DIR);
        return loadScripts(managementDir);
    }

    /**
     * Loads all ManagementScript providers from JARs in the given directory.
     */
    public static List<ManagementScript> loadScripts(Path managementDir) {
        if (!Files.isDirectory(managementDir)) {
            try {
                Files.createDirectories(managementDir);
                System.out.println("[ManagementScriptLoader] Created: " + managementDir.toAbsolutePath());
            } catch (IOException e) {
                System.err.println("[ManagementScriptLoader] Failed to create directory: " + e.getMessage());
            }
            return List.of();
        }

        closePreviousLoaders();

        List<Path> jars;
        try (var stream = Files.list(managementDir)) {
            jars = stream.filter(p -> p.toString().endsWith(".jar")).toList();
        } catch (IOException e) {
            System.err.println("[ManagementScriptLoader] Failed to scan directory: " + e.getMessage());
            return List.of();
        }

        if (jars.isEmpty()) {
            System.out.println("[ManagementScriptLoader] No JARs in " + managementDir.toAbsolutePath());
            return List.of();
        }

        System.out.println("[ManagementScriptLoader] Found " + jars.size() + " JAR(s) in " + managementDir.toAbsolutePath());

        ModuleFinder finder = ModuleFinder.of(managementDir);
        Set<ModuleReference> moduleReferences = finder.findAll();

        if (moduleReferences.isEmpty()) {
            System.out.println("[ManagementScriptLoader] No modules found in JARs.");
            return List.of();
        }

        List<ManagementScript> allScripts = new ArrayList<>();
        ModuleLayer bootLayer = ModuleLayer.boot();

        for (ModuleReference ref : moduleReferences) {
            String name = ref.descriptor().name();
            var location = ref.location();
            if (location.isEmpty()) continue;

            try {
                URL jarURL = location.get().toURL();
                Configuration cfg = bootLayer.configuration().resolve(
                        finder, ModuleFinder.of(), Collections.singleton(name));
                URLClassLoader classLoader = new URLClassLoader(new URL[]{jarURL});
                previousLoaders.add(classLoader);
                ModuleLayer layer = bootLayer.defineModulesWithOneLoader(cfg, classLoader);

                ServiceLoader<ManagementScript> loader = ServiceLoader.load(layer, ManagementScript.class);
                for (ManagementScript script : loader) {
                    allScripts.add(script);
                    System.out.println("[ManagementScriptLoader] Loaded: " + script.getClass().getName());
                }
            } catch (Exception e) {
                System.err.println("[ManagementScriptLoader] Failed to load module " + name + ": " + e.getMessage());
            }
        }

        return allScripts;
    }

    private static void closePreviousLoaders() {
        for (URLClassLoader loader : previousLoaders) {
            try {
                loader.close();
            } catch (IOException e) {
                System.err.println("[ManagementScriptLoader] Failed to close classloader: " + e.getMessage());
            }
        }
        previousLoaders.clear();
    }
}
