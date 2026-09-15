package com.botwithus.bot.core.runtime;

import com.botwithus.bot.core.runtime.ScriptJarStaging.Staged;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract staging exists for: a scripter can rebuild a JAR that the host
 * has already loaded.
 */
class ScriptJarStagingTest {

    private static final String LABEL = "scripts";

    /**
     * Never cleaned up. {@link #sourceStaysRebuildableAfterItsCopyIsLoaded}
     * deliberately leaves an undeletable JAR handle inside this root — that
     * leak is the behaviour under test — so cleanup would fail the test it is
     * proving. The leftovers are staging directories, which the next run's
     * dead-pid sweep removes.
     */
    @TempDir(cleanup = CleanupMode.NEVER)
    Path stagingRoot;

    @TempDir
    Path scriptsDir;

    /**
     * The invariant staging exists to hold, on every platform: the file a
     * module layer is defined over — and therefore the file whose handle can
     * never be closed — is the copy, never the JAR the scripter rebuilds.
     */
    @Test
    @DisplayName("the layer is defined over the staged copy, not the source JAR")
    void loadsFromTheStagedCopyNotTheSource() throws Exception {
        Path source = compileModuleJar();
        Staged staged = new ScriptJarStaging(LABEL, stagingRoot).stage(List.of(source), scriptsDir);

        Path loaded = loadOneClassFrom(staged.dir());

        assertEquals(staged.dir().resolve(source.getFileName().toString()), loaded);
        assertTrue(loaded.startsWith(stagingRoot), "loaded from staging, not from " + scriptsDir);
    }

    /**
     * The regression this whole mechanism exists for, asserted where it bites.
     * Defining a module layer over a JAR and loading one class from it opens a
     * handle nothing can close, and on Windows an open handle denies delete —
     * which is exactly what Gradle's {@code Copy} does to install a rebuilt
     * JAR. POSIX unlinks an open file happily, so only Windows can tell the
     * two files apart.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("on Windows the staged copy locks and the source stays rebuildable")
    void onWindowsOnlyTheStagedCopyLocks() throws Exception {
        Path source = compileModuleJar();
        Staged staged = new ScriptJarStaging(LABEL, stagingRoot).stage(List.of(source), scriptsDir);
        Path copy = staged.dir().resolve(source.getFileName().toString());

        loadOneClassFrom(staged.dir());

        assertThrows(IOException.class, () -> Files.delete(copy),
                "sanity: loading a module layer over a JAR really does lock it, "
                        + "so this test has teeth");
        assertDoesNotThrow(() -> Files.delete(source),
                "the JAR the scripter rebuilds must never be the one holding the handle");
    }

    @Test
    @DisplayName("a staged copy maps back to the JAR the scripter built")
    void sourceOfMapsCopyBackToTheBuiltJar() throws IOException {
        Path source = writeEmptyJar("named.jar");
        Staged staged = new ScriptJarStaging(LABEL, stagingRoot).stage(List.of(source), scriptsDir);

        assertEquals(source, staged.sourceOf(staged.dir().resolve("named.jar")),
                "errors must name the path the user recognises, not the copy");
    }

    @Test
    @DisplayName("a JAR that cannot be copied is reported, not dropped")
    void uncopyableJarIsReported() throws IOException {
        Path vanished = scriptsDir.resolve("gone.jar");
        Staged staged = new ScriptJarStaging(LABEL, stagingRoot).stage(List.of(vanished), scriptsDir);

        assertEquals(Set.of(vanished), staged.failures().keySet());
        assertTrue(staged.sourceByFileName().isEmpty());
    }

    @Test
    @DisplayName("leftovers from a dead process are swept, the live one's are not")
    void sweepsOnlyDeadGenerations() throws IOException {
        // pid 0 is never a live Windows or Linux user process, so this stands in
        // for a host that exited without cleaning up after itself.
        Path dead = Files.createDirectories(stagingRoot.resolve(LABEL + "-0-1"));
        Path foreign = Files.createDirectories(
                stagingRoot.resolve(LABEL + "-" + ProcessHandle.current().pid() + "-99"));
        Path unparseable = Files.createDirectories(stagingRoot.resolve("not-a-generation"));

        Staged staged = new ScriptJarStaging(LABEL, stagingRoot).stage(List.of(), scriptsDir);

        assertFalse(Files.exists(dead), "a dead process's staging directory is swept");
        assertFalse(Files.exists(foreign), "our own earlier generations are swept");
        assertTrue(Files.exists(unparseable), "an unrecognised directory is left alone");
        assertTrue(Files.isDirectory(staged.dir()), "the new generation is created after the sweep");
    }

    /**
     * Loads exactly one class out of {@code dir}, the way LocalScriptLoader
     * does, and returns the JAR the layer was defined over.
     */
    private static Path loadOneClassFrom(Path dir) throws Exception {
        ModuleFinder finder = ModuleFinder.of(dir);
        ModuleReference ref = finder.findAll().iterator().next();
        String name = ref.descriptor().name();
        ModuleLayer boot = ModuleLayer.boot();
        Configuration cfg = boot.configuration()
                .resolve(finder, ModuleFinder.of(), Set.of(name));
        URL jarUrl = ref.location().orElseThrow().toURL();
        try (URLClassLoader parent = new URLClassLoader(new URL[]{jarUrl})) {
            ModuleLayer layer = boot.defineModulesWithOneLoader(cfg, parent);
            layer.findLoader(name).loadClass("staged.Probe");
        }
        return Path.of(ref.location().orElseThrow());
    }

    /**
     * Compiles a self-contained module JAR into the scripts directory. It
     * depends on nothing, so the test needs no module path beyond the JDK.
     */
    private Path compileModuleJar() throws IOException {
        Path src = Files.createDirectories(scriptsDir.resolve("src").resolve("staged"));
        Files.writeString(src.getParent().resolve("module-info.java"),
                "module staged.probe { exports staged; }", StandardCharsets.UTF_8);
        Files.writeString(src.resolve("Probe.java"),
                "package staged; public class Probe { }", StandardCharsets.UTF_8);

        Path classes = scriptsDir.resolve("classes");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null,
                        "-d", classes.toString(),
                        src.getParent().resolve("module-info.java").toString(),
                        src.resolve("Probe.java").toString()),
                "the probe module must compile");

        Path jar = scriptsDir.resolve("probe.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            addEntry(out, classes, "module-info.class");
            addEntry(out, classes, "staged/Probe.class");
        }
        return jar;
    }

    private static void addEntry(JarOutputStream out, Path classes, String name) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(Files.readAllBytes(classes.resolve(name)));
        out.closeEntry();
    }

    private Path writeEmptyJar(String name) throws IOException {
        Path jar = scriptsDir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("marker.txt"));
            out.write("x".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
