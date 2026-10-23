package com.botwithus.bot.core.cache;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Points {@link RunningClientCache} at the <em>real</em> game installs on the
 * machine running the suite.
 *
 * <p>{@link RunningClientCacheTest} builds its own install shapes, which proves
 * the code is self-consistent and nothing more — a resolver that passes only
 * against directory trees the test invented has not been shown to understand the
 * client. These cases assert against installs nobody here created: the
 * {@code preferences.cfg} was written by the client, and the cache directories
 * were filled by it.</p>
 *
 * <p>Every case skips when its install is absent, so this is safe in the
 * CI-run {@code test} task on a machine with no game on it. <b>A skip is not a
 * pass.</b> When this class is the evidence for a change, read the case report
 * and confirm it says {@code passed}.</p>
 *
 * <p>No path here is hardcoded. Locations are derived from the environment the
 * same way {@link GameCacheCandidates} derives them, which keeps this repo —
 * which is public — free of any particular machine's drive layout, and lets the
 * cases run on another developer's install.</p>
 */
class RunningClientCacheInstallTest {

    private static final String PROGRAM_DATA_VARIABLE = "ProgramData";
    private static final String PROGRAM_FILES_X86_VARIABLE = "ProgramFiles(x86)";
    private static final String JAGEX_DIR = "Jagex";
    private static final String LAUNCHER_DIR = "launcher";
    private static final String BETA_DIR = "BETA";
    private static final String CLIENT_EXE = "rs2client.exe";
    private static final String STEAM_DIR = "Steam";
    private static final String STEAM_APPS_DIR = "steamapps";
    private static final String STEAM_COMMON_DIR = "common";

    private static Optional<Path> environmentDirectory(String variable) {
        String value = System.getenv(variable);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(value));
    }

    /** The launcher directory of the Jagex-launcher install, when one is present. */
    private static Optional<Path> jagexLauncher() {
        return environmentDirectory(PROGRAM_DATA_VARIABLE)
                .map(root -> root.resolve(JAGEX_DIR).resolve(LAUNCHER_DIR));
    }

    /** Where a RuneScape launcher directory would sit inside a Steam library. */
    private static Path steamLauncher(Path library) {
        return library.resolve(STEAM_APPS_DIR).resolve(STEAM_COMMON_DIR)
                .resolve(GameCacheCandidates.DEFAULT_GAME_NAME).resolve(LAUNCHER_DIR);
    }

    /** The Jagex-launcher directory, skipping the case when this is not Windows. */
    private static Path assumeJagexLauncher() {
        Optional<Path> launcher = jagexLauncher();
        assumeTrue(launcher.isPresent(), "%ProgramData% is unset; not a Windows machine");
        return launcher.get();
    }

    private static Path assumeClientAt(Path launcherDirectory) {
        Path executable = launcherDirectory.resolve(CLIENT_EXE);
        assumeTrue(Files.isRegularFile(executable),
                "no client installed at " + executable + " on this machine");
        return executable;
    }

    // ------------------------------------------------ The two real installs

    /**
     * The Jagex-launcher install. Its {@code preferences.cfg} sits beside the
     * executable and names a {@code cache_folder} one level above the cache.
     */
    @Test
    void theJagexLauncherInstallResolvesToItsOwnCache() {
        Path launcher = assumeJagexLauncher();
        Path executable = assumeClientAt(launcher);

        Optional<Path> resolved = RunningClientCache.forExecutable(executable);

        assertTrue(resolved.isPresent(),
                "a client that is installed and has run must resolve a cache directory");
        assertTrue(CacheSourceResolver.holdsGameCache(resolved.get()),
                "the resolved directory must actually hold js5-*.jcache files — the whole "
                        + "point is that existence is not the predicate");
        Path expected = GameCacheCandidates.cacheFolder(
                        GameCacheCandidates.preferencesIn(launcher)).orElseThrow()
                .resolve(GameCacheCandidates.DEFAULT_GAME_NAME);
        assertEquals(expected, resolved.get(),
                "the cache must be <cache_folder>/RuneScape as the client's own "
                        + "preferences.cfg names it, not a location this host assumed");
    }

    /**
     * The Steam install, which is the case a hardcoded path gets wrong: its
     * {@code cache_folder} is inside the Steam library, wherever the user put
     * that library, and the cache leaf repeats the game name.
     */
    @Test
    void aSteamInstallResolvesToTheCacheInsideItsOwnLibrary() {
        Optional<Path> steamClient = GameCacheCandidates.steamLibraries(System::getenv).stream()
                .map(RunningClientCacheInstallTest::steamLauncher)
                .map(launcher -> launcher.resolve(CLIENT_EXE))
                .filter(Files::isRegularFile)
                .findFirst();
        assumeTrue(steamClient.isPresent(), "no Steam RuneScape install on this machine");
        Path executable = steamClient.get();

        Optional<Path> resolved = RunningClientCache.forExecutable(executable);

        assertTrue(resolved.isPresent(), "a Steam client install must resolve a cache directory");
        assertTrue(CacheSourceResolver.holdsGameCache(resolved.get()),
                "the resolved Steam cache must hold index files");
        Path library = executable.getParent().getParent();
        assertTrue(resolved.get().startsWith(library),
                "the Steam cache lives inside the library the client was installed into ("
                        + library + "), which is exactly what a fixed path cannot know");
    }

    /**
     * Beta: the executable lives one directory below the {@code preferences.cfg}
     * that governs it, and its cache leaf carries the variant.
     *
     * <p>This is the case the walk-up and the variant heuristic exist for, and
     * the only real evidence either has. Getting it wrong is not "a stale
     * cache" — beta is a different content build, so a beta client reading the
     * live cache returns plausible wrong values silently.</p>
     */
    @Test
    void aBetaClientResolvesToTheBetaCacheRatherThanTheLiveOne() {
        Path launcher = assumeJagexLauncher();
        Path executable = assumeClientAt(launcher.resolve(BETA_DIR));
        Path cacheFolder = GameCacheCandidates.cacheFolder(
                GameCacheCandidates.preferencesIn(launcher)).orElseThrow();
        Path betaCache = cacheFolder.resolve(
                GameCacheCandidates.DEFAULT_GAME_NAME + "-" + BETA_DIR);
        assumeTrue(CacheSourceResolver.holdsGameCache(betaCache),
                "no populated beta cache at " + betaCache + " on this machine");

        Optional<Path> resolved = RunningClientCache.forExecutable(executable);

        assertEquals(Optional.of(betaCache), resolved,
                "the beta client must not be handed the live cache at "
                        + cacheFolder.resolve(GameCacheCandidates.DEFAULT_GAME_NAME));
    }

    // -------------------------------------------------------------- Decoys

    /**
     * A leftover {@code steamapps\common\RuneScape\launcher} from an uninstalled
     * copy, with no client and no cache under it.
     *
     * <p>This is the decoy a locator that tests for directory <em>existence</em>
     * picks. It is asserted on the real leftover when one is present, rather
     * than on a directory this test created, because the point is that the shape
     * occurs in the wild.</p>
     */
    @Test
    void aLeftoverSteamLauncherDirectoryResolvesNothing() {
        Optional<Path> decoy = environmentDirectory(PROGRAM_FILES_X86_VARIABLE)
                .map(root -> root.resolve(STEAM_DIR))
                .map(RunningClientCacheInstallTest::steamLauncher)
                .filter(Files::isDirectory)
                .filter(launcher -> !Files.isRegularFile(
                        GameCacheCandidates.preferencesIn(launcher)));
        assumeTrue(decoy.isPresent(), "no leftover Steam launcher directory on this machine");

        Optional<Path> resolved = RunningClientCache.forExecutable(decoy.get().resolve(CLIENT_EXE));

        assertTrue(resolved.isEmpty(),
                decoy.get() + " exists but holds no client preferences and no cache; a "
                        + "locator that selected it would open a handle answering "
                        + "\"not found\" to every lookup");
    }
}
