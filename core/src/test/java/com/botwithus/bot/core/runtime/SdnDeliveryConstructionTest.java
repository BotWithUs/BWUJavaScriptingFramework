package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.core.crypto.DefinedBundle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The delivery path from a defined jar to its scripts, through the real
 * {@code SDNScriptLoader.toDelivery}. On a stock JDK a jar's loader is not the SDN
 * loader, so its providers load from the unnamed module, as a non-modular delivered
 * jar's would; the construction path under test is the same either way.
 */
class SdnDeliveryConstructionTest {

    private static final Duration BUDGET = Duration.ofMillis(300);
    /** How long the slow probe's constructor sleeps: well past {@link #BUDGET}. */
    private static final long SLOW_MILLIS = 3000;
    private static final long TEST_TIMEOUT_SECONDS = 20;
    private static final String SERVICES = "META-INF/services/com.botwithus.bot.api.BotScript";

    @TempDir
    Path dir;

    /**
     * Fails if {@code jarOf} constructs inline rather than within the budget: the
     * call would take the constructor's whole sleep and report the script loaded.
     */
    @Test
    @Timeout(TEST_TIMEOUT_SECONDS)
    void aScriptWhoseConstructorOverrunsTheBudget_isAStall_andTheCallReturnsOnTime() throws Exception {
        try (URLClassLoader loader = loaderFor("Slow", "java.util.concurrent.TimeUnit.MILLISECONDS.sleep("
                + SLOW_MILLIS + "L);")) {
            Instant start = Instant.now();

            SdnDelivery delivery = SDNScriptLoader.toDelivery(bundleOf(loader), BUDGET);

            Duration took = Duration.between(start, Instant.now());
            assertTrue(took.toMillis() < SLOW_MILLIS, () -> "took " + took.toMillis() + " ms");
            assertEquals(List.of(new SdnDelivery.Stall(0, "probe.Slow")), delivery.stalls());
            assertTrue(delivery.jars().isEmpty());
        }
    }

    /** A constructor that returns is loaded, and each copy is a new instance. */
    @Test
    void aScriptThatConstructs_isLoaded_andItsCopiesAreNewInstances() throws Exception {
        try (URLClassLoader loader = loaderFor("Quick", "")) {
            SdnDelivery delivery = SDNScriptLoader.toDelivery(bundleOf(loader), BUDGET);

            SdnDelivery.Jar jar = delivery.jars().getFirst();
            BotScript copy = jar.copies().getFirst().get();
            assertEquals("probe.Quick", copy.getClass().getName());
            assertNotSame(jar.scripts().getFirst(), copy);
            assertTrue(delivery.stalls().isEmpty());
        }
    }

    /** A constructor that throws costs that script only, and is not a stall. */
    @Test
    void aScriptWhoseConstructorThrows_isSkipped_notStalled() throws Exception {
        try (URLClassLoader loader = loaderFor("Broken", "if (true) { throw new IllegalStateException(\"no\"); }")) {
            SdnDelivery delivery = SDNScriptLoader.toDelivery(bundleOf(loader), BUDGET);

            assertTrue(delivery.jars().isEmpty());
            assertTrue(delivery.stalls().isEmpty());
        }
    }

    private static DefinedBundle bundleOf(ClassLoader loader) {
        return new DefinedBundle(1, List.of(new DefinedBundle.Jar(0, loader)));
    }

    /** A loader over a jar holding one BotScript provider whose constructor runs {@code body}. */
    private URLClassLoader loaderFor(String name, String body) throws IOException, URISyntaxException {
        Path src = dir.resolve(name + "-src");
        Files.createDirectories(src.resolve("probe"));
        Files.writeString(src.resolve("probe/" + name + ".java"), "package probe;\n"
                + "public class " + name + " implements com.botwithus.bot.api.BotScript {\n"
                + "  public " + name + "() throws Exception { " + body + " }\n"
                + "  public void onStart(com.botwithus.bot.api.ScriptContext c) {}\n"
                + "  public int onLoop() { return -1; }\n"
                + "  public void onStop() {}\n"
                + "}\n");
        Path classes = dir.resolve(name + "-classes");
        Files.createDirectories(classes);
        String api = Path.of(BotScript.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, "-cp", api, "-d", classes.toString(),
                src.resolve("probe/" + name + ".java").toString()), "the probe must compile");
        Path jar = dir.resolve(name + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("probe/" + name + ".class"));
            out.write(Files.readAllBytes(classes.resolve("probe/" + name + ".class")));
            out.closeEntry();
            out.putNextEntry(new JarEntry(SERVICES));
            out.write(("probe." + name).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return new URLClassLoader(new URL[]{jar.toUri().toURL()}, BotScript.class.getClassLoader());
    }
}
