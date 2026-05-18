package com.botwithus.bot.core.resolver.install;

import com.botwithus.bot.core.resolver.MavenCoord;
import com.botwithus.bot.core.resolver.Repository;
import com.botwithus.bot.core.resolver.TestRepoFixture;
import com.botwithus.bot.core.resolver.driver.MavenRepositoryDriver;
import com.botwithus.bot.core.resolver.driver.RepositoryDriver;
import com.botwithus.bot.core.resolver.pgp.PgpVerifier;
import com.botwithus.bot.core.resolver.pipeline.Resolver;
import com.botwithus.bot.core.resolver.transport.FileTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScriptInstallerOutdatedTest {

    @TempDir
    Path tempDir;

    private Path repoRoot;
    private Path scriptsDir;
    private Path stagingRoot;
    private Path indexFile;
    private TestRepoFixture fixture;
    private ScriptInstaller installer;

    @BeforeEach
    void setUp() {
        repoRoot = tempDir.resolve("repo");
        scriptsDir = tempDir.resolve("scripts");
        stagingRoot = tempDir.resolve("staging");
        indexFile = tempDir.resolve(".installed.json");
        Repository repo = Repository.mavenRelease("test", repoRoot.toUri(), false);
        Map<String, RepositoryDriver> drivers = Map.of(MavenRepositoryDriver.TYPE_ID, new MavenRepositoryDriver());
        Resolver resolver = new Resolver(List.of(repo), new FileTransport(), drivers, PgpVerifier.ALWAYS_REJECT, stagingRoot);
        InstalledIndex index = new InstalledIndex(indexFile);
        fixture = new TestRepoFixture(repoRoot);
        installer = new ScriptInstaller(resolver, scriptsDir, index, () -> {}, Clock.systemUTC());
    }

    @Test
    void listOutdated_emptyWhenAllUpToDate() throws IOException {
        fixture.publish("com.example", "wc", "1.0.0",
                TestRepoFixture.buildJar("com.example", "wc", "1.0.0"));
        installer.install(MavenCoord.of("com.example", "wc"));

        assertTrue(installer.listOutdated().isEmpty());
    }

    @Test
    void listOutdated_returnsEntriesWithNewerVersions() throws IOException {
        fixture.publish("com.example", "wc", "1.0.0",
                TestRepoFixture.buildJar("com.example", "wc", "1.0.0"));
        installer.install(MavenCoord.of("com.example", "wc"));
        fixture.publish("com.example", "wc", "2.0.0",
                TestRepoFixture.buildJar("com.example", "wc", "2.0.0"));

        List<ScriptInstaller.OutdatedEntry> stale = installer.listOutdated();
        assertEquals(1, stale.size());
        assertEquals("1.0.0", stale.get(0).installed().version());
        assertEquals("2.0.0", stale.get(0).latestVersion());
    }

    @Test
    void listOutdated_returnsEmptyWhenNothingInstalled() {
        assertTrue(installer.listOutdated().isEmpty());
    }
}
