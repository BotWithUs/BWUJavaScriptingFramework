package com.botwithus.bot.core.resolver.driver;

import com.botwithus.bot.core.resolver.MavenCoord;
import com.botwithus.bot.core.resolver.Repository;
import com.botwithus.bot.core.resolver.transport.FileTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MavenRepositoryDriverTest {

    @TempDir
    Path tempDir;

    private Path repoRoot;
    private Repository repo;
    private MavenRepositoryDriver driver;

    @BeforeEach
    void setUp() {
        repoRoot = tempDir.resolve("repo");
        repo = Repository.mavenRelease("test", repoRoot.toUri(), false);
        driver = new MavenRepositoryDriver();
    }

    @Test
    void typeIdIsMaven() {
        assertEquals("maven", driver.typeId());
    }

    @Test
    void locateJarBuildsCanonicalMavenUrl() {
        MavenCoord coord = MavenCoord.of("com.example", "art");
        ArtifactLocation loc = driver.locateJar(repo, coord, "1.2.3");
        assertInstanceOf(ArtifactLocation.Url.class, loc);
        URI uri = ((ArtifactLocation.Url) loc).uri();
        assertTrue(uri.toString().endsWith("com/example/art/1.2.3/art-1.2.3.jar"));
    }

    @Test
    void locateChecksumAppendsSha256Suffix() {
        MavenCoord coord = MavenCoord.of("com.example", "art");
        ArtifactLocation loc = driver.locateChecksum(repo, coord, "1.0");
        URI uri = ((ArtifactLocation.Url) loc).uri();
        assertTrue(uri.toString().endsWith("art-1.0.jar.sha256"));
    }

    @Test
    void locateLegacyChecksumAppendsSha1Suffix() {
        MavenCoord coord = MavenCoord.of("com.example", "art");
        ArtifactLocation loc = driver.locateLegacyChecksum(repo, coord, "1.0");
        URI uri = ((ArtifactLocation.Url) loc).uri();
        assertTrue(uri.toString().endsWith("art-1.0.jar.sha1"));
    }

    @Test
    void locateSignatureAppendsAscSuffix() {
        MavenCoord coord = MavenCoord.of("com.example", "art");
        ArtifactLocation loc = driver.locateSignature(repo, coord, "1.0");
        URI uri = ((ArtifactLocation.Url) loc).uri();
        assertTrue(uri.toString().endsWith("art-1.0.jar.asc"));
    }

    @Test
    void listVersionsParsesPublishedMetadata() throws IOException {
        publishMetadata("com.example", "art", "1.2.0");
        ListVersionsResult result = driver.listVersions(
                repo, MavenCoord.of("com.example", "art"), new FileTransport(), Optional.empty()).join();
        ListVersionsResult.Ok ok = assertInstanceOf(ListVersionsResult.Ok.class, result);
        assertEquals("1.2.0", ok.listing().bestRelease().orElseThrow());
    }

    @Test
    void listVersionsReturnsNotIndexedForMissingMetadata() {
        ListVersionsResult result = driver.listVersions(
                repo, MavenCoord.of("com.absent", "art"), new FileTransport(), Optional.empty()).join();
        assertInstanceOf(ListVersionsResult.NotIndexed.class, result);
    }

    @Test
    void listVersionsReturnsMalformedForBadXml() throws IOException {
        Path mdDir = repoRoot.resolve("com/example/art");
        Files.createDirectories(mdDir);
        Files.writeString(mdDir.resolve("maven-metadata.xml"), "<not-real-xml");
        ListVersionsResult result = driver.listVersions(
                repo, MavenCoord.of("com.example", "art"), new FileTransport(), Optional.empty()).join();
        assertInstanceOf(ListVersionsResult.Malformed.class, result);
    }

    @Test
    void searchReturnsEmptyWhenRepoHasNoSearchEndpoint() {
        assertTrue(driver.search(repo).isEmpty());
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
