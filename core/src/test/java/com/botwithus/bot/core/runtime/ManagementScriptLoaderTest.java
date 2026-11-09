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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementScriptLoaderTest {

    private static final Instant MODIFIED = Instant.parse("2020-03-04T05:06:07Z");

    @TempDir
    Path tmp;

    private ManagementLoadReport load(Path managementDir) {
        return ManagementScriptLoader.loadReport(managementDir,
                new ScriptJarStaging("management-test", tmp.resolve("staged")));
    }

    @Test
    void missingFolder_isCreated_andReportsNothing() {
        Path dir = ManagementScriptLoader.managementDirIn(tmp.resolve("scripts"));

        ManagementLoadReport report = load(dir);

        assertSame(ManagementLoadReport.EMPTY, report);
        assertTrue(Files.isDirectory(dir));
    }

    @Test
    void everyJarThatDoesNotLoad_isReportedWithItsSourceFileAndTime() throws Exception {
        Path dir = Files.createDirectories(ManagementScriptLoader.managementDirIn(tmp.resolve("scripts")));
        Path plain = TestJars.plain(dir, "no-providers.jar", MODIFIED);
        TestJars.corrupt(dir, "half-written.jar");

        ManagementLoadReport report = load(dir);
        Map<String, ManagementLoadResult> byName = report.failures().stream().collect(Collectors.toMap(
                r -> r.jar().getFileName().toString(), Function.identity()));

        assertAll(
                () -> assertEquals(2, report.results().size()),
                () -> assertTrue(report.scripts().isEmpty()),
                () -> assertEquals(plain, byName.get("no-providers.jar").jar()),
                () -> assertEquals(Optional.of(MODIFIED), byName.get("no-providers.jar").lastModified()),
                () -> assertTrue(byName.get("no-providers.jar").error().orElseThrow().getMessage()
                        .contains("no ManagementScript providers")),
                () -> assertInstanceOf(FindException.class,
                        byName.get("half-written.jar").error().orElseThrow()));
    }

    @Test
    void aModuleThatRequiresTheHost_carriesADiagnosticNamingItAndItsSourceJar() throws Exception {
        Path dir = Files.createDirectories(ManagementScriptLoader.managementDirIn(tmp.resolve("scripts")));
        Path work = tmp.resolve("work");
        Path host = TestModules.hostStandIn(dir, work);
        Path reacher = TestModules.reacher(dir, work, host);
        TestModules.bystander(dir, work);

        Map<String, ManagementLoadResult> byName = load(dir).results().stream().collect(Collectors.toMap(
                r -> r.jar().getFileName().toString(), Function.identity()));

        List<String> diagnostics = byName.get("reacher.jar").diagnostics();
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        String message = diagnostics.getFirst();
        assertAll(
                () -> assertTrue(message.contains("'" + TestModules.REACHER + "'"), message),
                () -> assertTrue(message.contains(reacher.toString()), message),
                () -> assertTrue(byName.get("bystander.jar").diagnostics().isEmpty(),
                        byName.get("bystander.jar").diagnostics().toString()));
    }

    /** One finding per reaching JAR, reported only against that JAR. */
    @Test
    void severalPlainJars_eachIsReportedOnce_andOnlyAgainstItself() throws Exception {
        Path dir = Files.createDirectories(ManagementScriptLoader.managementDirIn(tmp.resolve("scripts")));
        Path a = TestJars.plain(dir, "plain-a.jar", MODIFIED);
        TestJars.plain(dir, "plain-b.jar", MODIFIED);
        TestJars.plain(dir, HostInternalsCheck.HOST_MODULE + ".jar", MODIFIED);

        Map<String, ManagementLoadResult> byName = byFileName(load(dir));

        List<String> ofA = byName.get("plain-a.jar").diagnostics();
        assertAll(
                () -> assertEquals(1, ofA.size(), ofA.toString()),
                () -> assertTrue(ofA.getFirst().contains(a.toString()), ofA.toString()),
                () -> assertEquals(2, byName.values().stream().mapToInt(r -> r.diagnostics().size()).sum(),
                        "one finding per reaching JAR"));
    }

    /** A layer that resolves but then fails to define still reports what the check found. */
    @Test
    void aLayerThatFailsToDefineAfterResolving_stillCarriesItsDiagnostic() throws Exception {
        Path dir = Files.createDirectories(ManagementScriptLoader.managementDirIn(tmp.resolve("scripts")));
        Path work = tmp.resolve("work");
        TestModules.splitPackage(dir, work, TestModules.hostStandIn(dir, work));

        ManagementLoadResult result = byFileName(load(dir)).get("split.jar");

        assertAll(
                () -> assertInstanceOf(LayerInstantiationException.class, result.error().orElseThrow()),
                () -> assertEquals(1, result.diagnostics().size(), result.diagnostics().toString()));
    }

    private static Map<String, ManagementLoadResult> byFileName(ManagementLoadReport report) {
        return report.results().stream().collect(Collectors.toMap(
                r -> r.jar().getFileName().toString(), Function.identity()));
    }
}
