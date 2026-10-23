package com.botwithus.bot.core.runtime;

import com.botwithus.bot.core.crypto.SdnLoader;
import com.botwithus.bot.core.crypto.SdnModule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.module.FindException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Module-layer loading of a delivered jar, over a stand-in for the runtime's
 * SDN loader.
 *
 * <p>The real loader decrypts inside the patched runtime, which a stock test JVM
 * does not have. What this host code depends on is the loader's module contract,
 * and {@link ContractLoader} honours exactly that contract over a plaintext jar:
 * one loader backs one module, and it answers {@code findClass(moduleName, name)}
 * for its own module. The descriptor and finder come from the jar itself, as the
 * runtime's {@code descriptor()} / {@code moduleFinder()} would produce them. The
 * service is {@link Runnable} rather than the script SPI because the test JVM
 * runs the api module on the class path, where no layer could resolve
 * {@code requires com.botwithus.bot.api}.
 */
class SdnBundleProvidersTest {

    private static final String MODULE = "sdn.probe";
    private static final String MODULAR_PROVIDER = "sdnprobe.Probe";
    private static final String LEGACY_PROVIDER = "legacyprobe.Legacy";

    @TempDir
    Path dir;

    @Test
    void modularJar_loadsItsProviderAsANamedModuleOfItsOwnLoader() throws Exception {
        Path jar = modularJar();
        try (ContractLoader loader = new ContractLoader(jar)) {
            List<Runnable> found = SdnBundleProviders.load(loader, Optional.of(moduleOf(jar)),
                    ModuleLayer.boot(), Runnable.class);

            assertEquals(1, found.size());
            Module module = found.getFirst().getClass().getModule();
            assertAll(
                    () -> assertEquals(MODULAR_PROVIDER, found.getFirst().getClass().getName()),
                    () -> assertTrue(module.isNamed(), "the provider must be in a named module"),
                    () -> assertEquals(MODULE, module.getName()),
                    () -> assertSame(loader, found.getFirst().getClass().getClassLoader(),
                            "the jar's own loader must define it"),
                    () -> assertNotSame(ModuleLayer.boot(), module.getLayer(),
                            "the jar must get a layer of its own"));
        }
    }

    @Test
    void nonModularJar_stillLoadsThroughServicesIntoTheUnnamedModule() throws Exception {
        try (ContractLoader loader = new ContractLoader(legacyJar())) {
            List<Runnable> found = SdnBundleProviders.load(loader, Optional.empty(), ModuleLayer.boot(),
                    Runnable.class);

            assertEquals(1, found.size());
            assertAll(
                    () -> assertEquals(LEGACY_PROVIDER, found.getFirst().getClass().getName()),
                    () -> assertFalse(found.getFirst().getClass().getModule().isNamed()),
                    () -> assertSame(loader, found.getFirst().getClass().getClassLoader()));
        }
    }

    /**
     * A service lookup on a loader walks its parents too. A provider found there
     * belongs to some other jar, and crediting it to this one would pair the
     * wrong script with this jar's catalogue entry.
     */
    @Test
    void nonModularJar_aProviderOnlyItsParentHas_isNotCreditedToIt() throws Exception {
        try (URLClassLoader parent = new URLClassLoader(new URL[]{legacyJar().toUri().toURL()},
                getClass().getClassLoader());
             URLClassLoader child = new URLClassLoader(new URL[]{emptyJar().toUri().toURL()}, parent)) {
            assertTrue(SdnBundleProviders.load(child, Optional.empty(), ModuleLayer.boot(), Runnable.class)
                    .isEmpty());
        }
    }

    /** It is refused, not retried without the module boundary its author declared. */
    @Test
    void modularJar_requiringAModuleTheHostLacks_throwsRatherThanLoadingUnnamed() throws Exception {
        ModuleDescriptor orphan = ModuleDescriptor.newModule("sdn.orphan")
                .requires("no.such.module")
                .packages(Set.of("orphan"))
                .build();
        SdnModule module = new SdnModule(orphan, finderOf(orphan));
        try (ContractLoader loader = new ContractLoader(emptyJar())) {
            assertThrows(FindException.class,
                    () -> SdnBundleProviders.load(loader, Optional.of(module), ModuleLayer.boot(), Runnable.class));
        }
    }

    /** On a stock JDK, and on a runtime without the module methods, every jar takes the legacy path. */
    @Test
    void moduleOf_onARuntimeWithoutTheSdnLoader_isEmpty() throws Exception {
        try (ContractLoader loader = new ContractLoader(emptyJar())) {
            assertEquals(Optional.empty(), SdnLoader.moduleOf(loader));
        }
    }

    // Fixtures -------------------------------------------------------------

    /** The module the jar declares, as the runtime's descriptor()/moduleFinder() would report it. */
    private static SdnModule moduleOf(Path jar) {
        ModuleFinder finder = ModuleFinder.of(jar);
        ModuleReference ref = finder.find(MODULE).orElseThrow();
        return new SdnModule(ref.descriptor(), finder);
    }

    private static ModuleFinder finderOf(ModuleDescriptor descriptor) {
        ModuleReference ref = new ModuleReference(descriptor, null) {
            @Override
            public ModuleReader open() throws IOException {
                throw new IOException("never opened: resolution fails first");
            }
        };
        return new ModuleFinder() {
            @Override
            public Optional<ModuleReference> find(String name) {
                return descriptor.name().equals(name) ? Optional.of(ref) : Optional.empty();
            }

            @Override
            public Set<ModuleReference> findAll() {
                return Set.of(ref);
            }
        };
    }

    private Path modularJar() throws IOException {
        Path src = dir.resolve("modular-src");
        Files.createDirectories(src.resolve("sdnprobe"));
        Files.writeString(src.resolve("module-info.java"),
                "module " + MODULE + " { provides java.lang.Runnable with " + MODULAR_PROVIDER + "; }");
        Files.writeString(src.resolve("sdnprobe/Probe.java"),
                "package sdnprobe; public class Probe implements Runnable { public void run() {} }");
        Path classes = compile(src, "module-info.java", "sdnprobe/Probe.java");
        Path jar = dir.resolve("modular.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            addFile(out, classes, "module-info.class");
            addFile(out, classes, "sdnprobe/Probe.class");
        }
        return jar;
    }

    private Path legacyJar() throws IOException {
        Path src = dir.resolve("legacy-src");
        Files.createDirectories(src.resolve("legacyprobe"));
        Files.writeString(src.resolve("legacyprobe/Legacy.java"),
                "package legacyprobe; public class Legacy implements Runnable { public void run() {} }");
        Path classes = compile(src, "legacyprobe/Legacy.java");
        Path jar = dir.resolve("legacy.jar");
        if (Files.exists(jar)) {
            return jar;
        }
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            addFile(out, classes, "legacyprobe/Legacy.class");
            out.putNextEntry(new JarEntry("META-INF/services/java.lang.Runnable"));
            out.write(LEGACY_PROVIDER.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private Path emptyJar() throws IOException {
        Path jar = dir.resolve("empty.jar");
        if (!Files.exists(jar)) {
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
                out.flush();
            }
        }
        return jar;
    }

    private static Path compile(Path src, String... files) throws IOException {
        Path classes = src.resolveSibling(src.getFileName() + "-classes");
        Files.createDirectories(classes);
        String[] args = new String[files.length + 2];
        args[0] = "-d";
        args[1] = classes.toString();
        for (int i = 0; i < files.length; i++) {
            args[i + 2] = src.resolve(files[i]).toString();
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, args), "the probe sources must compile");
        return classes;
    }

    private static void addFile(JarOutputStream out, Path classes, String name) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(Files.readAllBytes(classes.resolve(name)));
        out.closeEntry();
    }

    /**
     * Stands in for the runtime's SDN loader over a plaintext jar. The one thing it
     * adds to {@link URLClassLoader} is the part of the SDN loader's contract a
     * module layer needs: answering {@code findClass(moduleName, name)} for its own
     * module, which {@code ServiceLoader.load(layer, ...)} relies on.
     */
    private static final class ContractLoader extends URLClassLoader {

        ContractLoader(Path jar) throws IOException {
            super(new URL[]{jar.toUri().toURL()}, SdnBundleProvidersTest.class.getClassLoader());
        }

        @Override
        protected Class<?> findClass(String moduleName, String name) {
            try {
                return findClass(name);
            } catch (ClassNotFoundException e) {
                return null;
            }
        }
    }
}
