package com.botwithus.bot.core.resolver.metadata;

import com.botwithus.bot.core.resolver.MavenCoord;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Parses {@code META-INF/maven/<groupId>/<artifactId>/pom.properties} out
 * of a JAR. Used as a fallback when a JAR is present in {@code scripts/}
 * but missing from the install-index sidecar — {@code scripts adopt} reads
 * this to reconstruct the install entry.
 */
public record PomProperties(MavenCoord coord) {

    private static final String PREFIX = "META-INF/maven/";
    private static final String FILENAME = "pom.properties";
    private static final String KEY_GROUP_ID = "groupId";
    private static final String KEY_ARTIFACT_ID = "artifactId";
    private static final String KEY_VERSION = "version";

    /**
     * Reads the first {@code pom.properties} entry from a JAR.
     *
     * @return populated record, or empty if the JAR does not contain one
     * @throws IOException for unreadable JAR files
     */
    public static Optional<PomProperties> read(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = findPomPropertiesEntry(zip);
            if (entry == null) {
                return Optional.empty();
            }
            try (InputStream in = zip.getInputStream(entry)) {
                Properties props = new Properties();
                props.load(in);
                String g = props.getProperty(KEY_GROUP_ID);
                String a = props.getProperty(KEY_ARTIFACT_ID);
                String v = props.getProperty(KEY_VERSION);
                if (g == null || a == null || v == null
                        || g.isBlank() || a.isBlank() || v.isBlank()) {
                    return Optional.empty();
                }
                return Optional.of(new PomProperties(MavenCoord.of(g, a, v)));
            }
        }
    }

    private static ZipEntry findPomPropertiesEntry(ZipFile zip) {
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            String name = e.getName();
            if (!e.isDirectory() && name.startsWith(PREFIX) && name.endsWith("/" + FILENAME)) {
                return e;
            }
        }
        return null;
    }
}
