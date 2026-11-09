package com.botwithus.bot.core.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.LayerInstantiationException;
import java.lang.module.FindException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class LocalScriptLoaderTest {

    private static final Instant OLDER = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant NEWER = Instant.parse("2021-06-01T12:00:00Z");

    @TempDir
    Path tmp;

    /** Staged under the test's own folder, never the real {@code ~/.botwithus/staged-scripts}. */
    private LoadReport load(Path scriptsDir) {
        return LocalScriptLoader.loadReport(scriptsDir, new ScriptJarStaging("test", tmp.resolve("staged")));
    }

    private Path scriptsDir() throws Exception {
        return Files.createDirectories(tmp.resolve("scripts"));
    }

    private static Map<String, ScriptLoadResult> byFileName(LoadReport report) {
        return report.results().stream().collect(Collectors.toMap(
                r -> r.jar().getFileName().toString(), Function.identity()));
    }

    @Test
    void emptyDirYieldsEmptyReport() throws Exception {
        LoadReport report = load(scriptsDir());
        assertTrue(report.results().isEmpty());
        assertTrue(report.scripts().isEmpty());
        assertTrue(report.failures().isEmpty());
    }

    @Test
    void brokenJarSurfacedAsFailure() throws Exception {
        Path jar = TestJars.plain(scriptsDir(), "not-a-module.jar", OLDER);

        LoadReport report = load(jar.getParent());
        assertEquals(1, report.failures().size());
        ScriptLoadResult failure = report.failures().getFirst();
        assertTrue(failure.error().isPresent());
        assertEquals(jar.getFileName().toString(), failure.jar().getFileName().toString());
    }

    @Test
    void nonExistentDirIsCreatedAndYieldsEmptyReport() {
        Path scripts = tmp.resolve("nested").resolve("scripts");
        LoadReport report = load(scripts);
        assertTrue(Files.isDirectory(scripts));
        assertSame(LoadReport.EMPTY, report);
    }

    @Test
    void result_carriesTheSourceJarsModifiedTime_notTheLoadTime() throws Exception {
        Path jar = TestJars.plain(scriptsDir(), "dated.jar", OLDER);

        ScriptLoadResult result = load(jar.getParent()).results().getFirst();

        assertEquals(jar, result.jar(), "the source JAR, not the staged copy");
        assertEquals(Optional.of(OLDER), result.lastModified());
    }

    @Test
    void anUnreadableJar_failsAlone_andTheOtherJarsStillLoad() throws Exception {
        Path dir = scriptsDir();
        TestJars.corrupt(dir, "half-written.jar");
        TestJars.plain(dir, "other.jar", OLDER);

        Map<String, ScriptLoadResult> results = byFileName(load(dir));

        assertEquals(2, results.size());
        assertInstanceOf(FindException.class, results.get("half-written.jar").error().orElseThrow());
        assertTrue(results.get("other.jar").error().orElseThrow().getMessage().contains("no BotScript providers"),
                "other.jar got as far as its own module layer");
    }

    @Test
    void twoJarsOfOneModule_theNewestIsLoaded_andTheOlderIsReported() throws Exception {
        Path dir = scriptsDir();
        TestJars.plain(dir, "my-script-1.0.jar", OLDER);
        TestJars.plain(dir, "my-script-1.1.jar", NEWER);

        Map<String, ScriptLoadResult> results = byFileName(load(dir));

        String olderError = results.get("my-script-1.0.jar").error().orElseThrow().getMessage();
        assertTrue(olderError.contains("my-script-1.1.jar"), olderError);
        String newerError = results.get("my-script-1.1.jar").error().orElseThrow().getMessage();
        assertTrue(newerError.contains("no BotScript providers"), "the newer copy was loaded: " + newerError);
    }

    /**
     * The diagnostic names the JAR the scripter built, not the staged copy the
     * layer was defined over, and only the reaching module carries one.
     */
    @Test
    void aModuleThatRequiresTheHost_carriesADiagnosticNamingItAndItsSourceJar() throws Exception {
        Path dir = scriptsDir();
        Path work = tmp.resolve("work");
        Path host = TestModules.hostStandIn(dir, work);
        Path reacher = TestModules.reacher(dir, work, host);
        TestModules.bystander(dir, work);

        Map<String, ScriptLoadResult> results = byFileName(load(dir));

        List<String> diagnostics = results.get("reacher.jar").diagnostics();
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        String message = diagnostics.getFirst();
        assertAll(
                () -> assertTrue(message.contains("'" + TestModules.REACHER + "'"), message),
                () -> assertTrue(message.contains(reacher.toString()), message),
                () -> assertTrue(results.get("bystander.jar").diagnostics().isEmpty(),
                        results.get("bystander.jar").diagnostics().toString()),
                () -> assertTrue(results.get("host-stand-in.jar").diagnostics().isEmpty(),
                        results.get("host-stand-in.jar").diagnostics().toString()));
    }

    /**
     * Each JAR is resolved against every staged JAR, and resolving one automatic
     * module pulls in all of them. Only the JAR being loaded may be reported
     * against itself: one finding per reaching JAR, never one per pair.
     */
    @Test
    void severalPlainJars_eachIsReportedOnce_andOnlyAgainstItself() throws Exception {
        Path dir = scriptsDir();
        Path a = TestJars.plain(dir, "plain-a.jar", OLDER);
        Path b = TestJars.plain(dir, "plain-b.jar", OLDER);
        TestJars.plain(dir, HostInternalsCheck.HOST_MODULE + ".jar", OLDER);

        Map<String, ScriptLoadResult> results = byFileName(load(dir));

        List<String> ofA = results.get("plain-a.jar").diagnostics();
        List<String> ofB = results.get("plain-b.jar").diagnostics();
        assertAll(
                () -> assertEquals(1, ofA.size(), ofA.toString()),
                () -> assertTrue(ofA.getFirst().contains(a.toString()), ofA.toString()),
                () -> assertEquals(1, ofB.size(), ofB.toString()),
                () -> assertTrue(ofB.getFirst().contains(b.toString()), ofB.toString()),
                () -> assertEquals(2, results.values().stream().mapToInt(r -> r.diagnostics().size()).sum(),
                        "one finding per reaching JAR"));
    }

    /** A layer that resolves but then fails to define still reports what the check found. */
    @Test
    void aLayerThatFailsToDefineAfterResolving_stillCarriesItsDiagnostic() throws Exception {
        Path dir = scriptsDir();
        Path work = tmp.resolve("work");
        TestModules.splitPackage(dir, work, TestModules.hostStandIn(dir, work));

        ScriptLoadResult result = byFileName(load(dir)).get("split.jar");

        assertAll(
                () -> assertInstanceOf(LayerInstantiationException.class, result.error().orElseThrow()),
                () -> assertEquals(1, result.diagnostics().size(), result.diagnostics().toString()),
                () -> assertTrue(result.diagnostics().getFirst().contains("'" + TestModules.SPLIT + "'"),
                        result.diagnostics().toString()));
    }

    @Test
    void aModuleThatDeclaresAProviderNoneOfWhichLoad_stillCarriesItsDiagnostic() throws Exception {
        Path dir = scriptsDir();
        Path work = tmp.resolve("work");
        Path host = TestModules.hostStandIn(dir, work);
        TestModules.provider(dir, work, host, TestModules.apiStandIn(dir, work));

        ScriptLoadResult result = byFileName(load(dir)).get("provider.jar");

        assertAll(
                () -> assertTrue(result.error().orElseThrow().getMessage().contains("declares 'provides BotScript'"),
                        result.error().orElseThrow().toString()),
                () -> assertEquals(1, result.diagnostics().size(), result.diagnostics().toString()),
                () -> assertTrue(result.diagnostics().getFirst().contains("'" + TestModules.PROVIDER + "'"),
                        result.diagnostics().toString()));
    }
}
