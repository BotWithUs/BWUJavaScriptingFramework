package com.botwithus.bot.core.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class LocalScriptLoaderTest {

    @Test
    void emptyDirYieldsEmptyReport(@TempDir Path tmp) {
        LoadReport report = LocalScriptLoader.loadReport(tmp);
        assertTrue(report.results().isEmpty());
        assertTrue(report.scripts().isEmpty());
        assertTrue(report.failures().isEmpty());
    }

    @Test
    void brokenJarSurfacedAsFailure(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("not-a-module.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar), simpleManifest())) {
            // No module-info — just a plain manifest. ModuleFinder will refuse it.
        }
        assertTrue(Files.size(jar) > 0);

        LoadReport report = LocalScriptLoader.loadReport(tmp);
        assertEquals(1, report.failures().size());
        ScriptLoadResult failure = report.failures().getFirst();
        assertTrue(failure.error().isPresent());
        assertEquals(jar.getFileName().toString(), failure.jar().getFileName().toString());
    }

    @Test
    void nonExistentDirIsCreatedAndYieldsEmptyReport(@TempDir Path tmp) {
        Path scripts = tmp.resolve("nested").resolve("scripts");
        LoadReport report = LocalScriptLoader.loadReport(scripts);
        assertTrue(Files.isDirectory(scripts));
        assertSame(LoadReport.EMPTY, report);
    }

    private static Manifest simpleManifest() {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        return m;
    }
}
