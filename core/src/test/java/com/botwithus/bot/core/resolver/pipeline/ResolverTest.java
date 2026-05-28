package com.botwithus.bot.core.resolver.pipeline;

import com.botwithus.bot.core.resolver.MavenCoord;
import com.botwithus.bot.core.resolver.Repository;
import com.botwithus.bot.core.resolver.ResolveOutcome;
import com.botwithus.bot.core.resolver.TestRepoFixture;
import com.botwithus.bot.core.resolver.driver.MavenRepositoryDriver;
import com.botwithus.bot.core.resolver.driver.RepositoryDriver;
import com.botwithus.bot.core.resolver.pgp.PgpVerifier;
import com.botwithus.bot.core.resolver.transport.FileTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ResolverTest {

    @TempDir
    Path tempDir;

    private Path repoRoot;
    private Path staging;
    private Repository repo;
    private Resolver resolver;
    private TestRepoFixture fixture;

    @BeforeEach
    void setUp() {
        repoRoot = tempDir.resolve("repo");
        staging = tempDir.resolve("staging");
        repo = Repository.mavenRelease("test", repoRoot.toUri(), false);
        Map<String, RepositoryDriver> drivers = Map.of(MavenRepositoryDriver.TYPE_ID, new MavenRepositoryDriver());
        resolver = new Resolver(List.of(repo), new FileTransport(), drivers, PgpVerifier.ALWAYS_REJECT, staging);
        fixture = new TestRepoFixture(repoRoot);
    }

    @Test
    void resolvesPublishedArtifact() throws IOException {
        byte[] jarBytes = TestRepoFixture.buildJar("com.example", "art", "1.0.0");
        byte[] expectedDigest = fixture.publish("com.example", "art", "1.0.0", jarBytes);

        ResolveOutcome outcome = resolver.resolve(MavenCoord.of("com.example", "art"));
        ResolveOutcome.Resolved r = assertInstanceOf(ResolveOutcome.Resolved.class, outcome);
        assertEquals("1.0.0", r.artifact().resolvedVersion());
        assertArrayEquals(expectedDigest, r.artifact().sha256());
        assertTrue(Files.exists(r.artifact().jar()));
        assertEquals(jarBytes.length, Files.size(r.artifact().jar()));
    }

    @Test
    void mismatchedSha256IsReported() throws IOException {
        byte[] jarBytes = TestRepoFixture.buildJar("com.example", "art", "1.0.0");
        fixture.publishWithBadChecksum("com.example", "art", "1.0.0", jarBytes);

        ResolveOutcome outcome = resolver.resolve(MavenCoord.of("com.example", "art"));
        assertInstanceOf(ResolveOutcome.ChecksumMismatch.class, outcome);
    }

    @Test
    void missingVersionMetadataYieldsNotFound() {
        ResolveOutcome outcome = resolver.resolve(MavenCoord.of("com.absent", "art"));
        assertInstanceOf(ResolveOutcome.NotFound.class, outcome);
    }

    @Test
    void metadataPresentButJarMissingYieldsNotFound() throws IOException {
        fixture.publishMetadataOnly("com.example", "ghost", "1.0.0");
        ResolveOutcome outcome = resolver.resolve(MavenCoord.of("com.example", "ghost"));
        assertInstanceOf(ResolveOutcome.NotFound.class, outcome);
    }

    @Test
    void emptyRepositoryListYieldsNotFound() {
        Map<String, RepositoryDriver> drivers = Map.of(MavenRepositoryDriver.TYPE_ID, new MavenRepositoryDriver());
        Resolver empty = new Resolver(List.of(), new FileTransport(), drivers, PgpVerifier.ALWAYS_REJECT, staging);
        ResolveOutcome outcome = empty.resolve(MavenCoord.of("com.example", "art"));
        assertInstanceOf(ResolveOutcome.NotFound.class, outcome);
    }

    @Test
    void fallsBackToSha1WhenSha256Absent() throws IOException {
        byte[] jarBytes = TestRepoFixture.buildJar("com.example", "legacy", "1.0.0");
        fixture.publishSha1Only("com.example", "legacy", "1.0.0", jarBytes);

        ResolveOutcome outcome = resolver.resolve(MavenCoord.of("com.example", "legacy"));
        assertInstanceOf(ResolveOutcome.Resolved.class, outcome);
    }

    @Test
    void explicitVersionWinsOverMetadata() throws IOException {
        fixture.publish("com.example", "art", "1.0.0", TestRepoFixture.buildJar("com.example", "art", "1.0.0"));
        byte[] v11 = TestRepoFixture.buildJar("com.example", "art", "1.1.0");
        fixture.publish("com.example", "art", "1.1.0", v11);

        ResolveOutcome outcome = resolver.resolve(MavenCoord.of("com.example", "art", "1.0.0"));
        ResolveOutcome.Resolved r = assertInstanceOf(ResolveOutcome.Resolved.class, outcome);
        assertEquals("1.0.0", r.artifact().resolvedVersion());
    }

    @Test
    void maliciousMetadataVersionIsRejected() throws IOException {
        Path artifactDir = repoRoot.resolve("com/example/evil");
        Files.createDirectories(artifactDir);
        String md = """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>com.example</groupId>
                  <artifactId>evil</artifactId>
                  <versioning>
                    <latest>../../../../pwned</latest>
                    <release>../../../../pwned</release>
                    <versions>
                      <version>../../../../pwned</version>
                    </versions>
                  </versioning>
                </metadata>
                """;
        Files.writeString(artifactDir.resolve("maven-metadata.xml"), md);

        ResolveOutcome outcome = resolver.resolve(MavenCoord.of("com.example", "evil"));
        assertInstanceOf(ResolveOutcome.NotFound.class, outcome);
    }

    @Test
    void unknownDriverYieldsNotFound() {
        Repository custom = new Repository(
                "custom", repoRoot.toUri(), "no-such-driver", false, false,
                Optional.empty(), Optional.empty());
        Map<String, RepositoryDriver> drivers = Map.of(MavenRepositoryDriver.TYPE_ID, new MavenRepositoryDriver());
        Resolver r = new Resolver(List.of(custom), new FileTransport(), drivers, PgpVerifier.ALWAYS_REJECT, staging);

        ResolveOutcome outcome = r.resolve(MavenCoord.of("com.example", "art"));
        assertInstanceOf(ResolveOutcome.NotFound.class, outcome);
    }
}
