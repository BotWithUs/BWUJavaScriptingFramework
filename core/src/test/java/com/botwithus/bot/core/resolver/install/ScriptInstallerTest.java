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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ScriptInstallerTest {

    @TempDir
    Path tempDir;

    private Path repoRoot;
    private Path scriptsDir;
    private Path stagingRoot;
    private Path indexFile;
    private TestRepoFixture fixture;
    private Resolver resolver;
    private InstalledIndex index;
    private AtomicInteger changeCount;

    @BeforeEach
    void setUp() {
        repoRoot = tempDir.resolve("repo");
        scriptsDir = tempDir.resolve("scripts");
        stagingRoot = tempDir.resolve("staging");
        indexFile = tempDir.resolve(".installed.json");
        Repository repo = Repository.mavenRelease("test", repoRoot.toUri(), false);
        Map<String, RepositoryDriver> drivers = Map.of(MavenRepositoryDriver.TYPE_ID, new MavenRepositoryDriver());
        resolver = new Resolver(List.of(repo), new FileTransport(), drivers, PgpVerifier.ALWAYS_REJECT, stagingRoot);
        index = new InstalledIndex(indexFile);
        fixture = new TestRepoFixture(repoRoot);
        changeCount = new AtomicInteger();
    }

    private ScriptInstaller newInstaller() {
        return new ScriptInstaller(resolver, scriptsDir, index, changeCount::incrementAndGet, Clock.systemUTC());
    }

    private void publish(String version) throws IOException {
        fixture.publish("com.example", "wc-script", version,
                TestRepoFixture.buildJar("com.example", "wc-script", version));
    }

    @Test
    void install_freshArtifact_emitsInstalledAndCreatesJar() throws IOException {
        publish("1.0.0");
        ScriptInstaller installer = newInstaller();

        InstallResult result = installer.install(MavenCoord.of("com.example", "wc-script"));

        InstallResult.Installed installed = assertInstanceOf(InstallResult.Installed.class, result);
        assertEquals(scriptsDir.resolve("wc-script-1.0.0.jar"), installed.jar());
        assertTrue(Files.exists(installed.jar()));
        assertEquals(1, changeCount.get());

        assertTrue(Files.exists(indexFile));
        InstalledIndex reloaded = new InstalledIndex(indexFile);
        reloaded.load();
        assertEquals(1, reloaded.size());
        assertEquals("1.0.0", reloaded.find(MavenCoord.of("com.example", "wc-script"))
                .orElseThrow().version());
    }

    @Test
    void install_twice_emitsAlreadyInstalled() throws IOException {
        publish("1.0.0");
        ScriptInstaller installer = newInstaller();
        installer.install(MavenCoord.of("com.example", "wc-script"));

        InstallResult second = installer.install(MavenCoord.of("com.example", "wc-script", "1.0.0"));
        assertInstanceOf(InstallResult.AlreadyInstalled.class, second);
    }

    @Test
    void install_withNewerVersion_emitsUpdatedAndRemovesOldJar() throws IOException {
        publish("1.0.0");
        ScriptInstaller installer = newInstaller();
        installer.install(MavenCoord.of("com.example", "wc-script"));
        Path oldJar = scriptsDir.resolve("wc-script-1.0.0.jar");
        assertTrue(Files.exists(oldJar));

        publish("1.1.0");
        InstallResult result = installer.install(MavenCoord.of("com.example", "wc-script"));
        InstallResult.Updated updated = assertInstanceOf(InstallResult.Updated.class, result);
        assertEquals(scriptsDir.resolve("wc-script-1.1.0.jar"), updated.newJar());
        assertFalse(Files.exists(oldJar));
        assertTrue(Files.exists(updated.newJar()));
    }

    @Test
    void update_noNewerAvailable_emitsNoUpdateAvailable() throws IOException {
        publish("1.0.0");
        ScriptInstaller installer = newInstaller();
        installer.install(MavenCoord.of("com.example", "wc-script"));

        InstallResult result = installer.update(MavenCoord.of("com.example", "wc-script"));
        InstallResult.NoUpdateAvailable noUpdate = assertInstanceOf(InstallResult.NoUpdateAvailable.class, result);
        assertEquals("1.0.0", noUpdate.installedVersion());
    }

    @Test
    void update_newerAvailable_installsNewVersion() throws IOException {
        publish("1.0.0");
        ScriptInstaller installer = newInstaller();
        installer.install(MavenCoord.of("com.example", "wc-script"));
        publish("2.0.0");

        InstallResult result = installer.update(MavenCoord.of("com.example", "wc-script"));
        InstallResult.Updated updated = assertInstanceOf(InstallResult.Updated.class, result);
        assertEquals(scriptsDir.resolve("wc-script-2.0.0.jar"), updated.newJar());
    }

    @Test
    void update_notInstalled_emitsNotInstalled() {
        InstallResult result = newInstaller().update(MavenCoord.of("com.example", "nope"));
        assertInstanceOf(InstallResult.NotInstalled.class, result);
    }

    @Test
    void uninstall_removesJarAndIndexEntry() throws IOException {
        publish("1.0.0");
        ScriptInstaller installer = newInstaller();
        installer.install(MavenCoord.of("com.example", "wc-script"));
        Path jar = scriptsDir.resolve("wc-script-1.0.0.jar");
        assertTrue(Files.exists(jar));

        InstallResult result = installer.uninstall(MavenCoord.of("com.example", "wc-script"));
        assertInstanceOf(InstallResult.Uninstalled.class, result);
        assertFalse(Files.exists(jar));
        InstalledIndex reloaded = new InstalledIndex(indexFile);
        reloaded.load();
        assertEquals(0, reloaded.size());
    }

    @Test
    void uninstall_notInstalled_emitsNotInstalled() {
        InstallResult result = newInstaller().uninstall(MavenCoord.of("g", "a"));
        assertInstanceOf(InstallResult.NotInstalled.class, result);
    }

    @Test
    void install_resolveFailure_emitsResolveFailed() {
        InstallResult result = newInstaller().install(MavenCoord.of("com.absent", "thing"));
        assertInstanceOf(InstallResult.ResolveFailed.class, result);
    }

    @Test
    void onScriptsChangedCallbackFires() throws IOException {
        publish("1.0.0");
        ScriptInstaller installer = newInstaller();
        installer.install(MavenCoord.of("com.example", "wc-script"));
        installer.uninstall(MavenCoord.of("com.example", "wc-script"));
        assertEquals(2, changeCount.get());
    }
}
