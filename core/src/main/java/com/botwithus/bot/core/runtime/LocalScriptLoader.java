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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Discovers BotScript implementations from local JAR files in a scripts directory.
 * Each JAR is a Java module that {@code provides BotScript with ...}.
 * Loaded into a child ModuleLayer so ServiceLoader can find them.
 */
public final class LocalScriptLoader {

    private static final Logger log = LoggerFactory.getLogger(LocalScriptLoader.class);
    private static final String SCRIPTS_DIR_NAME = "scripts";
    private static final String SCRIPTS_DIR_PROPERTY = "botwithus.scripts.dir";
    private static final String USER_CONFIG_DIR_NAME = ".botwithus";

    /**
     * Track classloaders from previous loads so we can close them on reload.
     * The tracker is encapsulated in a typed helper so the mutable state is
     * not a raw list scattered across this class.
     */
    private static final PreviousLoaderTracker previousLoaders = new PreviousLoaderTracker();

    private LocalScriptLoader() {}

    /**
     * Pins the classloader that defined {@code script} so no later reload
     * closes it. Called by {@link ScriptRuntime} when a runner is abandoned:
     * the script's thread is still alive and can't be killed, so its loader
     * must outlive it. The script instance is its own provenance — no separate
     * runner-to-loader bookkeeping is needed.
     */
    static void pinLoaderOf(BotScript script) {
        if (script != null) {
            previousLoaders.pin(script.getClass().getClassLoader());
        }
    }

    /**
     * Loads all BotScript providers from JARs in the default {@code scripts/} directory.
     * Failures are dropped silently — use {@link #loadReport()} if you need
     * visibility into per-JAR errors.
     */
    public static List<BotScript> loadScripts() {
        return loadReport().scripts();
    }

    /**
     * Convenience overload of {@link #loadScripts()} that targets a specific
     * scripts directory. Failures are dropped silently — use
     * {@link #loadReport(Path)} for full per-JAR diagnostics.
     */
    public static List<BotScript> loadScripts(Path scriptsDir) {
        return loadReport(scriptsDir).scripts();
    }

    /**
     * Loads all BotScript providers from the default scripts directory and
     * returns a full per-JAR {@link LoadReport} including failures.
     */
    public static LoadReport loadReport() {
        return loadReport(resolveScriptsDir());
    }

    /**
     * Resolves the scripts directory. Checks (in order):
     * 1. System property {@code botwithus.scripts.dir}
     * 2. {@code scripts/} in the working directory (the build installs the
     *    example scripts there, so this is the normal dev path)
     * 3. Fallback: {@code ~/.botwithus/scripts}, so an installed app whose
     *    install folder may be read-only still has a writable drop point.
     *
     * <p>Every JAR in the resolved directory is loaded as fully-trusted code
     * with no signature or allow-list check, so the set of places this will
     * look is deliberately small. It used to walk up to three parent
     * directories looking for a {@code scripts/}, which meant anyone able to
     * create a directory in <em>any</em> ancestor of the working directory
     * could substitute the entire script source. That walk is gone; a dev
     * running from a subdirectory should pass {@code -D}{@value
     * #SCRIPTS_DIR_PROPERTY} explicitly.</p>
     */
    static Path resolveScriptsDir() {
        String override = System.getProperty(SCRIPTS_DIR_PROPERTY);
        if (override != null) {
            Path overridePath = Path.of(override);
            // Surface a non-default source so a repointed script dir is
            // visible in the log.
            log.warn("Scripts dir taken from -D{} override: {}",
                    SCRIPTS_DIR_PROPERTY, overridePath.toAbsolutePath());
            return overridePath;
        }
        Path candidate = Path.of("").toAbsolutePath().resolve(SCRIPTS_DIR_NAME);
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        return Path.of(System.getProperty("user.home"), USER_CONFIG_DIR_NAME, SCRIPTS_DIR_NAME);
    }

    /**
     * Loads all BotScript providers from JARs in the given directory and
     * returns a {@link LoadReport} that captures per-JAR successes and
     * failures (with stack traces). Replaces the silent {@code catch} that
     * used to swallow errors at the bottom of the module-load loop.
     */
    public static LoadReport loadReport(Path scriptsDir) {
        if (!Files.isDirectory(scriptsDir)) {
            log.info("Scripts directory not found: {}", scriptsDir.toAbsolutePath());
            log.info("Creating it — drop script JARs there and restart.");
            try {
                Files.createDirectories(scriptsDir);
            } catch (IOException e) {
                log.error("Failed to create scripts directory: {}", e.getMessage());
            }
            return LoadReport.EMPTY;
        }

        previousLoaders.closeAll();

        List<Path> jars = listJars(scriptsDir);
        if (jars.isEmpty()) {
            log.info("No JARs found in {}", scriptsDir.toAbsolutePath());
            return LoadReport.EMPTY;
        }
        log.info("Found {} JAR(s) in {}", jars.size(), scriptsDir.toAbsolutePath());

        ModuleFinder finder = ModuleFinder.of(scriptsDir);
        Set<ModuleReference> moduleReferences = finder.findAll();
        if (moduleReferences.isEmpty()) {
            log.info("No modules found in JARs. Ensure each JAR has a module-info with 'provides BotScript with ...'");
            return new LoadReport(jars.stream()
                    .map(j -> ScriptLoadResult.failure(j,
                            new IllegalStateException(
                                    "JAR is not a Java module — missing module-info.java with 'provides BotScript with ...'"),
                            List.of()))
                    .toList());
        }

        if (!coreDeclaresUsesBotScript()) {
            return LoadReport.EMPTY;
        }

        List<ScriptLoadResult> results = new ArrayList<>();
        ModuleLayer bootLayer = ModuleLayer.boot();
        for (ModuleReference ref : moduleReferences) {
            results.addAll(loadOneModule(ref, finder, bootLayer));
        }
        return new LoadReport(results);
    }

    private static List<Path> listJars(Path scriptsDir) {
        try (var stream = Files.list(scriptsDir)) {
            return stream.filter(p -> p.toString().endsWith(".jar")).toList();
        } catch (IOException e) {
            log.error("Failed to scan scripts directory: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Fail-fast: if this module (core) doesn't declare {@code uses BotScript},
     * ServiceLoader will silently return empty for ALL script JARs. Returns
     * {@code true} when the {@code uses} clause is present (or this module is
     * unnamed, in which case ServiceLoader works regardless).
     */
    private static boolean coreDeclaresUsesBotScript() {
        Module coreModule = LocalScriptLoader.class.getModule();
        if (!coreModule.isNamed()) {
            return true;
        }
        boolean declaresUses = coreModule.getDescriptor().uses()
                .contains(BotScript.class.getName());
        if (!declaresUses) {
            log.error("FATAL: Module '{}' is missing 'uses com.botwithus.bot.api.BotScript;' in module-info.java",
                    coreModule.getName());
            log.error("ServiceLoader will not discover any scripts without it!");
        }
        return declaresUses;
    }

    private static List<ScriptLoadResult> loadOneModule(ModuleReference ref, ModuleFinder finder,
                                                        ModuleLayer bootLayer) {
        String name = ref.descriptor().name();
        Path jar = ref.location().map(Path::of).orElse(Path.of(name));

        if (ref.location().isEmpty()) {
            return List.of(ScriptLoadResult.failure(jar,
                    new IllegalStateException("Module " + name + " has no resolvable location"),
                    List.of()));
        }

        try {
            URL jarURL = ref.location().get().toURL();
            Configuration cfg = bootLayer.configuration().resolve(
                    finder, ModuleFinder.of(), Collections.singleton(name));
            URLClassLoader classLoader = new URLClassLoader(new URL[]{jarURL});
            previousLoaders.add(classLoader);
            ModuleLayer layer = bootLayer.defineModulesWithOneLoader(cfg, classLoader);

            Optional<Module> module = layer.findModule(name);
            if (module.isEmpty()) {
                return List.of(ScriptLoadResult.failure(jar,
                        new IllegalStateException("Module " + name + " not found in defined layer"),
                        List.of()));
            }

            boolean providesBotScript = module.get().getDescriptor().provides().stream()
                    .anyMatch(p -> p.service().equals(BotScript.class.getName()));

            List<ScriptLoadResult> results = new ArrayList<>();
            ServiceLoader<BotScript> loader = ServiceLoader.load(layer, BotScript.class);
            for (BotScript script : loader) {
                log.info("Loaded: {}", script.getClass().getName());
                results.add(ScriptLoadResult.success(jar, script, List.of()));
            }

            if (results.isEmpty() && providesBotScript) {
                String msg = "Module '" + name
                        + "' declares 'provides BotScript' but ServiceLoader found 0 implementations";
                log.warn(msg);
                return List.of(ScriptLoadResult.failure(jar, new IllegalStateException(msg), List.of()));
            }
            if (results.isEmpty()) {
                String msg = "Module '" + name + "' contains no BotScript providers";
                log.info(msg);
                return List.of(ScriptLoadResult.failure(jar, new IllegalStateException(msg), List.of()));
            }
            return results;
        } catch (Exception e) {
            log.error("Failed to load module {}: {}", name, e.getMessage(), e);
            return List.of(ScriptLoadResult.failure(jar, e, List.of()));
        }
    }
}
