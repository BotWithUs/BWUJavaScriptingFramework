package com.botwithus.bot.core.resolver.pipeline;

import com.botwithus.bot.core.resolver.MavenCoord;
import com.botwithus.bot.core.resolver.RepoType;
import com.botwithus.bot.core.resolver.Repository;
import com.botwithus.bot.core.resolver.metadata.MavenMetadataParser;
import com.botwithus.bot.core.resolver.transport.FileMavenTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class VersionResolverTest {

    @TempDir
    Path tempDir;

    private Path repoRoot;
    private Path staging;
    private Repository repo;
    private VersionResolver resolver;

    @BeforeEach
    void setUp() {
        repoRoot = tempDir.resolve("repo");
        staging = tempDir.resolve("staging");
        repo = Repository.unauthenticated("test", repoRoot.toUri(), RepoType.RELEASE, false);
        resolver = new VersionResolver(new FileMavenTransport(), new MavenMetadataParser());
    }

    @Test
    void resolvesVersionFromMetadata() throws IOException {
        publishMetadata("com.example", "art", "1.2.0");
        VersionResolver.Result r = resolver.resolve(
                MavenCoord.of("com.example", "art"), repo, staging, Optional.empty());
        assertInstanceOf(VersionResolver.Result.Resolved.class, r);
        assertEquals("1.2.0", ((VersionResolver.Result.Resolved) r).version());
    }

    @Test
    void returnsResolvedWhenCoordAlreadyVersioned() {
        VersionResolver.Result r = resolver.resolve(
                MavenCoord.of("com.example", "art", "9.9.9"), repo, staging, Optional.empty());
        assertInstanceOf(VersionResolver.Result.Resolved.class, r);
        assertEquals("9.9.9", ((VersionResolver.Result.Resolved) r).version());
    }

    @Test
    void noMetadataReturnsNoMetadataResult() {
        VersionResolver.Result r = resolver.resolve(
                MavenCoord.of("com.absent", "art"), repo, staging, Optional.empty());
        assertInstanceOf(VersionResolver.Result.NoMetadata.class, r);
    }

    @Test
    void malformedMetadataIsReported() throws IOException {
        Path mdDir = repoRoot.resolve("com/example/art");
        Files.createDirectories(mdDir);
        Files.writeString(mdDir.resolve("maven-metadata.xml"), "<not-real-xml");
        VersionResolver.Result r = resolver.resolve(
                MavenCoord.of("com.example", "art"), repo, staging, Optional.empty());
        assertInstanceOf(VersionResolver.Result.MetadataMalformed.class, r);
    }

    private void publishMetadata(String groupId, String artifactId, String version) throws IOException {
        Path dir = repoRoot.resolve(groupId.replace('.', '/')).resolve(artifactId);
        Files.createDirectories(dir);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<metadata>\n"
                + "  <groupId>" + groupId + "</groupId>\n"
                + "  <artifactId>" + artifactId + "</artifactId>\n"
                + "  <versioning>\n"
                + "    <release>" + version + "</release>\n"
                + "    <versions><version>" + version + "</version></versions>\n"
                + "  </versioning>\n"
                + "</metadata>\n";
        Files.writeString(dir.resolve("maven-metadata.xml"), xml);
    }
}
