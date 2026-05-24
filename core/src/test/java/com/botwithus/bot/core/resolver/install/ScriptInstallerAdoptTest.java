package com.botwithus.bot.core.resolver.install;

import com.botwithus.bot.core.resolver.InstallResult;
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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.*;

class ScriptInstallerAdoptTest {

    @TempDir
    Path tempDir;

    private Path repoRoot;
    private Path scriptsDir;
    private Path stagingRoot;
    private Path indexFile;
    private TestRepoFixture fixture;
    private ScriptInstaller installer;
    private InstalledIndex index;

    @BeforeEach
    void setUp() {
        repoRoot = tempDir.resolve("repo");
        scriptsDir = tempDir.resolve("scripts");
        stagingRoot = tempDir.resolve("staging");
        indexFile = tempDir.resolve(".installed.json");
        Repository repo = Repository.mavenRelease("test", repoRoot.toUri(), false);
        Map<String, RepositoryDriver> drivers = Map.of(MavenRepositoryDriver.TYPE_ID, new MavenRepositoryDriver());
        Resolver resolver = new Resolver(List.of(repo), new FileTransport(), drivers, PgpVerifier.ALWAYS_REJECT, stagingRoot);
        index = new InstalledIndex(indexFile);
        fixture = new TestRepoFixture(repoRoot);
        installer = new ScriptInstaller(resolver, scriptsDir, index, () -> {}, Clock.systemUTC());
    }

    @Test
    void adopt_addsManuallyDroppedJarToIndex() throws IOException {
        Files.createDirectories(scriptsDir);
        byte[] jarBytes = TestRepoFixture.buildJar("com.example", "manual", "1.0.0");
        Path jar = scriptsDir.resolve("manual-1.0.0.jar");
        Files.write(jar, jarBytes);

        InstallResult result = installer.adopt("manual-1.0.0.jar");
        InstallResult.Installed installed = assertInstanceOf(InstallResult.Installed.class, result);
        assertEquals("com.example", installed.coord().groupId());
        assertEquals("manual", installed.coord().artifactId());
        assertEquals("1.0.0", installed.coord().version().orElseThrow());

        Optional<InstalledEntry> entry = index.find(MavenCoord.of("com.example", "manual"));
        assertTrue(entry.isPresent());
        assertEquals(ScriptInstaller.ADOPTED_REPO_ID, entry.get().repoId());
    }

    @Test
    void adopt_alreadyIndexedYieldsAlreadyInstalled() throws IOException {
        fixture.publish("com.example", "wc", "1.0.0",
                TestRepoFixture.buildJar("com.example", "wc", "1.0.0"));
        installer.install(MavenCoord.of("com.example", "wc"));

        InstallResult result = installer.adopt("wc-1.0.0.jar");
        assertInstanceOf(InstallResult.AlreadyInstalled.class, result);
    }

    @Test
    void adopt_missingJarYieldsIoError() {
        InstallResult result = installer.adopt("does-not-exist.jar");
        assertInstanceOf(InstallResult.IoError.class, result);
    }

    @Test
    void adopt_jarWithoutPomPropertiesYieldsIoError() throws IOException {
        Files.createDirectories(scriptsDir);
        Path jar = scriptsDir.resolve("plain.jar");
        try (OutputStream os = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(os)) {
            jos.putNextEntry(new ZipEntry("hello.txt"));
            jos.write("plain".getBytes());
            jos.closeEntry();
        }
        InstallResult result = installer.adopt("plain.jar");
        assertInstanceOf(InstallResult.IoError.class, result);
    }
}
