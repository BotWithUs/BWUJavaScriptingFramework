package com.botwithus.bot.core.resolver.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.*;

class PomPropertiesTest {

    @TempDir
    Path tempDir;

    @Test
    void readsCoordFromPomProperties() throws IOException {
        Path jar = buildJar("com.example", "my-script", "1.0.0",
                "groupId=com.example\nartifactId=my-script\nversion=1.0.0\n");
        Optional<PomProperties> result = PomProperties.read(jar);
        assertTrue(result.isPresent());
        assertEquals("com.example", result.get().coord().groupId());
        assertEquals("my-script", result.get().coord().artifactId());
        assertEquals("1.0.0", result.get().coord().version().orElseThrow());
    }

    @Test
    void returnsEmptyForJarWithoutPomProperties() throws IOException {
        Path jar = buildJarWithoutPomProperties();
        assertTrue(PomProperties.read(jar).isEmpty());
    }

    @Test
    void returnsEmptyForBlankFields() throws IOException {
        Path jar = buildJar("com.example", "art", "1.0",
                "groupId=\nartifactId=art\nversion=1.0\n");
        assertTrue(PomProperties.read(jar).isEmpty());
    }

    private Path buildJar(String groupId, String artifactId, String version, String propsContent) throws IOException {
        Path jar = tempDir.resolve("test.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (OutputStream os = Files.newOutputStream(jar);
             JarOutputStream out = new JarOutputStream(os, manifest)) {
            out.putNextEntry(new ZipEntry("META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties"));
            out.write(propsContent.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private Path buildJarWithoutPomProperties() throws IOException {
        Path jar = tempDir.resolve("plain.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (OutputStream os = Files.newOutputStream(jar);
             JarOutputStream out = new JarOutputStream(os, manifest)) {
            out.putNextEntry(new ZipEntry("hello.txt"));
            out.write("plain".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
