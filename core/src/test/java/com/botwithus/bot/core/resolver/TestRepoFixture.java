package com.botwithus.bot.core.resolver;

import com.botwithus.bot.core.resolver.metadata.ChecksumDigest;
import com.botwithus.bot.core.resolver.metadata.Sha1Digest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * Test helper that materializes a Maven-layout directory tree (suitable
 * for the {@code file://} transport) under a {@link java.nio.file.Path}.
 */
public final class TestRepoFixture {

    private final Path root;

    public TestRepoFixture(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    public Path root() {
        return root;
    }

    /**
     * Publishes a JAR + sha256 sidecar + maven-metadata.xml entry. Returns
     * the SHA-256 of the published JAR for assertions.
     */
    public byte[] publish(String groupId, String artifactId, String version, byte[] jarContents) throws IOException {
        Path artifactDir = root
                .resolve(groupId.replace('.', '/'))
                .resolve(artifactId)
                .resolve(version);
        Files.createDirectories(artifactDir);

        Path jarFile = artifactDir.resolve(artifactId + "-" + version + ".jar");
        Files.write(jarFile, jarContents);
        ChecksumDigest digest = ChecksumDigest.of(jarFile);
        Files.writeString(jarFile.resolveSibling(jarFile.getFileName() + ".sha256"), digest.toHex());

        appendMetadata(groupId, artifactId, version);
        return digest.sha256();
    }

    /** Same as {@link #publish} but writes a corrupt sha256 sidecar. */
    /**
     * Publishes a JAR with a SHA-1 sidecar but no SHA-256 — exercises the
     * legacy Maven Central fallback path.
     */
    public byte[] publishSha1Only(String groupId, String artifactId, String version, byte[] jarContents) throws IOException {
        Path artifactDir = root
                .resolve(groupId.replace('.', '/'))
                .resolve(artifactId)
                .resolve(version);
        Files.createDirectories(artifactDir);

        Path jarFile = artifactDir.resolve(artifactId + "-" + version + ".jar");
        Files.write(jarFile, jarContents);
        Sha1Digest digest = Sha1Digest.of(jarFile);
        Files.writeString(jarFile.resolveSibling(jarFile.getFileName() + ".sha1"), digest.toHex());

        appendMetadata(groupId, artifactId, version);
        return digest.sha1();
    }

    public void publishWithBadChecksum(String groupId, String artifactId, String version, byte[] jarContents) throws IOException {
        publish(groupId, artifactId, version, jarContents);
        Path jarFile = root
                .resolve(groupId.replace('.', '/'))
                .resolve(artifactId)
                .resolve(version)
                .resolve(artifactId + "-" + version + ".jar");
        Files.writeString(jarFile.resolveSibling(jarFile.getFileName() + ".sha256"),
                "0000000000000000000000000000000000000000000000000000000000000000  fake.jar");
    }

    /** Publishes metadata only — no jar. Used to test metadata/jar mismatch. */
    public void publishMetadataOnly(String groupId, String artifactId, String version) throws IOException {
        Path artifactDir = root.resolve(groupId.replace('.', '/')).resolve(artifactId);
        Files.createDirectories(artifactDir);
        appendMetadata(groupId, artifactId, version);
    }

    private void appendMetadata(String groupId, String artifactId, String latestVersion) throws IOException {
        Path mdFile = root
                .resolve(groupId.replace('.', '/'))
                .resolve(artifactId)
                .resolve("maven-metadata.xml");
        List<String> known = Files.exists(mdFile) ? readExistingVersions(mdFile) : new ArrayList<>();
        if (!known.contains(latestVersion)) {
            known.add(latestVersion);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<metadata>\n");
        sb.append("  <groupId>").append(groupId).append("</groupId>\n");
        sb.append("  <artifactId>").append(artifactId).append("</artifactId>\n");
        sb.append("  <versioning>\n");
        sb.append("    <latest>").append(latestVersion).append("</latest>\n");
        sb.append("    <release>").append(latestVersion).append("</release>\n");
        sb.append("    <versions>\n");
        for (String v : known) {
            sb.append("      <version>").append(v).append("</version>\n");
        }
        sb.append("    </versions>\n");
        sb.append("  </versioning>\n");
        sb.append("</metadata>\n");
        Files.writeString(mdFile, sb.toString(), StandardCharsets.UTF_8);
    }

    private static List<String> readExistingVersions(Path mdFile) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(mdFile)) {
            String trimmed = line.trim();
            String open = "<version>";
            String close = "</version>";
            if (trimmed.startsWith(open) && trimmed.endsWith(close)) {
                out.add(trimmed.substring(open.length(), trimmed.length() - close.length()));
            }
        }
        return out;
    }

    /**
     * Builds a tiny but valid JAR with a manifest and a pom.properties
     * entry — exercises the {@code PomProperties} fallback path.
     */
    public static byte[] buildJar(String groupId, String artifactId, String version) throws IOException {
        var bytes = new ByteArrayOutputStream();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            String pomPath = "META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties";
            jar.putNextEntry(new ZipEntry(pomPath));
            String props = "groupId=" + groupId + "\nartifactId=" + artifactId + "\nversion=" + version + "\n";
            writeAll(jar, props.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void writeAll(OutputStream os, byte[] data) throws IOException {
        os.write(data);
    }
}
