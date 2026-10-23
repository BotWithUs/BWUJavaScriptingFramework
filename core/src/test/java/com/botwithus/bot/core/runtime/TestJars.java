package com.botwithus.bot.core.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** JAR files on disk for loader tests. */
final class TestJars {

    private static final byte[] NOT_A_ZIP = {1, 2, 3};

    private TestJars() {}

    /**
     * A JAR with only a manifest. The module system reads it as an automatic
     * module named after the file ({@code my-script-1.0.jar} is module
     * {@code my.script}), which provides no scripts.
     */
    static Path plain(Path dir, String fileName, Instant modified) throws IOException {
        Path jar = dir.resolve(fileName);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.flush();
        }
        Files.setLastModifiedTime(jar, FileTime.from(modified));
        return jar;
    }

    /** A file named like a JAR that is not a readable archive — a JAR caught half-written. */
    static Path corrupt(Path dir, String fileName) throws IOException {
        return Files.write(dir.resolve(fileName), NOT_A_ZIP);
    }
}
