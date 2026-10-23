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
}
