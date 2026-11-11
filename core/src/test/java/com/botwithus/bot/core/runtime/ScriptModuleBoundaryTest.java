package com.botwithus.bot.core.runtime;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a script can do with the host module, observed in a host that runs it
 * the way the shipped one does.
 *
 * <p>The host module, {@value HostInternalsCheck#HOST_MODULE}, sits in the boot
 * layer that every script layer resolves against, and it exports its packages
 * to the host front-end only. This test starts a JVM with the real module's jar
 * on the module path, loads the {@link BoundaryProbes} through
 * {@link LocalScriptLoader}, and runs one loop of each. A probe the boundary
 * holds against fails with an access error; the control and the front-end run.
 * One JVM serves every case, so each test reads one line of its reply.</p>
 *
 * <p>The test JVM itself cannot host this: it runs the host module on the class
 * path, where every package is visible to everything.</p>
 */
class ScriptModuleBoundaryTest {

    private static final String HOST_JAR = "botwithus.test.hostJar";
    private static final String HOST_MODULE_PATH = "botwithus.test.hostModulePath";
    private static final String HOST_PATCH = "botwithus.test.hostPatch";
    private static final long HOST_TIMEOUT_SECONDS = 120;
    /** Joins a failed assertion's rule to the host's whole reply, which says why. */
    private static final String REPLIED = "; the host replied:" + System.lineSeparator();
    private static final String LINKAGE_REFUSED = IllegalAccessError.class.getName();
    private static final String LOOKUP_REFUSED = IllegalStateException.class.getName() + "/"
            + IllegalAccessException.class.getName();

    @TempDir
    static Path tmp;

    private static Map<String, String> outcomes;
    private static Map<String, String> failures;
    private static String reply;

    @BeforeAll
    static void runTheProbesInAHost() throws IOException, InterruptedException {
        Path hostJar = Path.of(required(HOST_JAR));
        List<Path> hostDeps = Arrays.stream(required(HOST_MODULE_PATH).split(File.pathSeparator))
                .map(Path::of)
                .toList();
        BoundaryProbes probes = new BoundaryProbes(Files.createDirectories(tmp.resolve("work")), hostDeps);
        Path frontEnd = probes.frontEnd(Files.createDirectories(tmp.resolve("host")), hostJar);
        Path scriptsDir = Files.createDirectories(tmp.resolve("scripts"));
        probes.writeProbes(scriptsDir, frontEnd);
        probes.namedLikeTheFrontEnd(scriptsDir);

        reply = runHost(hostJar, hostDeps, frontEnd, scriptsDir);
        outcomes = linesStarting(ScriptBoundaryHost.OUTCOME_PREFIX);
        failures = linesStarting(ScriptBoundaryHost.FAILURE_PREFIX);
    }

    @Test
    void aScriptUsingOnlyTheApi_runs() {
        assertScript(BoundaryProbes.CONTROL, ScriptBoundaryHost.ALLOWED, "a script using only the api must run");
    }

    @Test
    void aScriptThatRequiresTheHostModule_isRefusedItsTypes() {
        assertScript(BoundaryProbes.REQUIRES_HOST, LINKAGE_REFUSED,
                "a script that requires the host module must be refused its types");
    }

    @Test
    void aScriptThatRequiresOnlyTheFrontEnd_isRefusedTheHostModulesTypes() {
        assertScript(BoundaryProbes.REQUIRES_FRONT_END, LINKAGE_REFUSED,
                "a script that requires the front-end must be refused the host module's types");
    }

    @Test
    void aScriptWithNoModuleDescriptor_isRefusedTheHostModulesTypes() {
        assertScript(BoundaryProbes.AUTOMATIC, LINKAGE_REFUSED,
                "a script with no module descriptor must be refused the host module's types");
    }

    @Test
    void aScriptThatFindsAHostTypeByName_isRefusedItsMembers() {
        assertScript(BoundaryProbes.BY_NAME, LOOKUP_REFUSED,
                "a script that finds a host type by name must be refused its members");
    }

    @Test
    void theFrontEnd_usesTheHostModulesTypes() {
        assertEquals(ScriptBoundaryHost.ALLOWED,
                outcomes.get(ScriptBoundaryHost.HOST_LAYER + " " + BoundaryProbes.FRONT_END),
                () -> "the front-end must be able to use the host module's types" + REPLIED + reply);
    }

    @Test
    void aScriptNamedLikeTheFrontEnd_isNotLoaded() {
        String why = failures.get("named-like-front-end.jar");
        String asScript = ScriptBoundaryHost.SCRIPT_LAYER + " " + BoundaryProbes.FRONT_END;
        assertAll(
                () -> assertFalse(outcomes.containsKey(asScript),
                        () -> "a script named like the front-end must not load" + REPLIED + reply),
                () -> assertTrue(why != null && why.contains("has the name of a module the host provides"),
                        () -> "a script named like the front-end must be reported" + REPLIED + reply));
    }

    private static void assertScript(String module, String expected, String rule) {
        assertEquals(expected, outcomes.get(ScriptBoundaryHost.SCRIPT_LAYER + " " + module),
                () -> rule + " (" + module + ")" + REPLIED + reply);
    }

    private static String runHost(Path hostJar, List<Path> hostDeps, Path frontEnd, Path scriptsDir)
            throws IOException, InterruptedException {
        List<Path> modulePath = new ArrayList<>(List.of(hostJar, frontEnd));
        modulePath.addAll(hostDeps);
        Path output = tmp.resolve("host-output.txt");
        Process host = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + Files.createDirectories(tmp.resolve("home")),
                "--patch-module", HostInternalsCheck.HOST_MODULE + "=" + required(HOST_PATCH),
                "--module-path", String.join(File.pathSeparator, modulePath.stream().map(Path::toString).toList()),
                "--add-modules", BoundaryProbes.FRONT_END,
                "--module", HostInternalsCheck.HOST_MODULE + "/" + ScriptBoundaryHost.class.getName(),
                scriptsDir.toString())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        boolean exited = host.waitFor(HOST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            host.destroyForcibly();
        }
        String text = Files.readString(output, StandardCharsets.UTF_8);
        assertTrue(exited, () -> "the host did not exit: " + text);
        assertEquals(0, host.exitValue(), () -> "the host failed: " + text);
        return text;
    }

    private static Map<String, String> linesStarting(String prefix) {
        Map<String, String> found = new TreeMap<>();
        reply.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()))
                .forEach(line -> {
                    int split = line.indexOf('=');
                    found.put(line.substring(0, split), line.substring(split + 1));
                });
        return found;
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        assertNotNull(value, property + " is set by the Gradle test task; run this test through it");
        return value;
    }
}
