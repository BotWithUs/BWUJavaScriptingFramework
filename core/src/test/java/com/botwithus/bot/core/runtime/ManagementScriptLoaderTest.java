package com.botwithus.bot.core.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.module.FindException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
}
