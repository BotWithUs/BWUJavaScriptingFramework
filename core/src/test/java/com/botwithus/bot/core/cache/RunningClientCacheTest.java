package com.botwithus.bot.core.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how a running client's own {@code preferences.cfg} is turned into a cache
 * directory: where the file is looked for, which game-name leaf is chosen, and
 * every way the answer is declined rather than guessed.
 *
 * <p>Each case builds a whole install shape under a {@link TempDir}, so the
 * suite runs anywhere and asserts on structure rather than on this machine's
 * installs. {@link RunningClientCacheInstallTest} is the other half: it points
 * the same code at the <em>real</em> installs, because a shape this test invents
 * only proves the code is self-consistent.</p>
 */
class RunningClientCacheTest {

    /** An id no real cache would use; only its name matters to the glob. */
    private static final String AN_INDEX_FILE = "js5-7.jcache";

    private static final String LAUNCHER = "launcher";
    private static final String CLIENT_EXE = "rs2client.exe";

    /**
     * Writes a {@code preferences.cfg} into {@code directory} naming
     * {@code cacheFolder}, with the surrounding keys a real one carries so the
     * parser is exercised on more than a single line.
     */
    private static void writePreferences(Path directory, Path cacheFolder) throws IOException {
        Files.createDirectories(directory);
        Files.write(directory.resolve(GameCacheCandidates.PREFERENCES_FILE), List.of(
                "graphics_device=2882",
                "confirm_quit=0",
                "cache_folder=" + cacheFolder,
                "Language=0",
                "user_folder=" + cacheFolder));
    }

    /** Creates {@code directory} and puts one index file in it, making it a cache. */
    private static Path cacheDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.createFile(directory.resolve(AN_INDEX_FILE));
        return directory;
    }

    private static Path clientExecutable(Path directory) throws IOException {
        Files.createDirectories(directory);
        return Files.createFile(directory.resolve(CLIENT_EXE));
    }

    // ------------------------------------------ Where preferences.cfg lives

    @Test
    void aPreferencesFileBesideTheExecutableNamesTheCache(@TempDir Path root) throws IOException {
        Path install = root.resolve("Jagex");
        Path executable = clientExecutable(install.resolve(LAUNCHER));
        writePreferences(install.resolve(LAUNCHER), install);
        Path cache = cacheDirectory(install.resolve(GameCacheCandidates.DEFAULT_GAME_NAME));

        assertEquals(Optional.of(cache), RunningClientCache.forExecutable(executable),
                "the ordinary install shape: cfg beside the exe, cache at <cache_folder>/RuneScape");
    }

    @Test
    void aPreferencesFileOneLevelAboveTheExecutableStillApplies(@TempDir Path root)
            throws IOException {
        Path install = root.resolve("Jagex");
        Path launcher = install.resolve(LAUNCHER);
        writePreferences(launcher, install);
        Path executable = clientExecutable(launcher.resolve("BETA"));
        Path cache = cacheDirectory(install.resolve("RuneScape-BETA"));

        assertEquals(Optional.of(cache), RunningClientCache.forExecutable(executable),
                "the beta executable's own directory holds no preferences.cfg at all, so "
                        + "looking only beside the exe would resolve nothing");
    }

    @Test
    void anExecutableWithNoPreferencesAnywhereAboveItResolvesNothing(@TempDir Path root)
            throws IOException {
        Path executable = clientExecutable(root.resolve("some").resolve("where"));

        assertTrue(RunningClientCache.forExecutable(executable).isEmpty(),
                "no cfg means no answer — the caller falls through to the next tier");
    }

    @Test
    void aPreferencesFileWithNoCacheFolderKeyResolvesNothing(@TempDir Path root)
            throws IOException {
        Path launcher = root.resolve(LAUNCHER);
        Path executable = clientExecutable(launcher);
        Files.write(launcher.resolve(GameCacheCandidates.PREFERENCES_FILE),
                List.of("graphics_device=2882", "Language=0"));

        assertTrue(RunningClientCache.forExecutable(executable).isEmpty(),
                "a cfg that names no cache_folder is an ordinary state, not an error");
    }

    // ------------------------------------------------ Which leaf is chosen

    @Test
    void theVariantLeafWinsOverTheDefaultWhenBothHoldCaches(@TempDir Path root)
            throws IOException {
        Path install = root.resolve("Jagex");
        Path launcher = install.resolve(LAUNCHER);
        writePreferences(launcher, install);
        Path executable = clientExecutable(launcher.resolve("BETA"));
        cacheDirectory(install.resolve(GameCacheCandidates.DEFAULT_GAME_NAME));
        Path betaCache = cacheDirectory(install.resolve("RuneScape-BETA"));

        assertEquals(Optional.of(betaCache), RunningClientCache.forExecutable(executable),
                "beta is a different content build, not an older one — handing it the live "
                        + "cache would return plausible wrong ids rather than stale ones");
    }

    @Test
    void theDefaultLeafIsTakenWhenTheVariantHoldsNoCache(@TempDir Path root) throws IOException {
        Path install = root.resolve("Jagex");
        Path launcher = install.resolve(LAUNCHER);
        writePreferences(launcher, install);
        Path executable = clientExecutable(launcher.resolve("BETA"));
        Files.createDirectories(install.resolve("RuneScape-BETA"));
        Path liveCache = cacheDirectory(install.resolve(GameCacheCandidates.DEFAULT_GAME_NAME));

        assertEquals(Optional.of(liveCache), RunningClientCache.forExecutable(executable),
                "an empty variant directory must not win merely by existing");
    }

    @Test
    void theDeepestVariantDirectoryIsPreferredWhenNested(@TempDir Path root) throws IOException {
        Path install = root.resolve("Jagex");
        Path launcher = install.resolve(LAUNCHER);
        writePreferences(launcher, install);
        Path executable = clientExecutable(launcher.resolve("OUTER").resolve("INNER"));
        cacheDirectory(install.resolve("RuneScape-OUTER"));
        Path innermost = cacheDirectory(install.resolve("RuneScape-INNER"));

        assertEquals(Optional.of(innermost), RunningClientCache.forExecutable(executable),
                "the directory nearest the executable is the most specific thing said about "
                        + "which variant it is, so it is tried first");
    }

    @Test
    void aSoleUnnamedLeafIsTakenWhenNothingElseQualifies(@TempDir Path root) throws IOException {
        Path install = root.resolve("Jagex");
        Path launcher = install.resolve(LAUNCHER);
        writePreferences(launcher, install);
        Path executable = clientExecutable(launcher);
        Path onlyCache = cacheDirectory(install.resolve("SomeFutureVariant"));

        assertEquals(Optional.of(onlyCache), RunningClientCache.forExecutable(executable),
                "the game name is runtime-supplied, so an unrecognised leaf must still "
                        + "resolve when it is the only one that can be meant");
    }

    @Test
    void severalUnnamedLeavesDeclineRatherThanGuess(@TempDir Path root) throws IOException {
        Path install = root.resolve("Jagex");
        Path launcher = install.resolve(LAUNCHER);
        writePreferences(launcher, install);
        Path executable = clientExecutable(launcher);
        cacheDirectory(install.resolve("SomeFutureVariant"));
        cacheDirectory(install.resolve("AnotherFutureVariant"));

        assertTrue(RunningClientCache.forExecutable(executable).isEmpty(),
                "picking one of two would silently hand a client the wrong content build; "
                        + "declining lets the candidate list and live fallback answer instead");
    }

    // -------------------------------------------------------- The decoy

    @Test
    void aCacheFolderWhoseDirectoriesHoldNoIndexFilesResolvesNothing(@TempDir Path root)
            throws IOException {
        Path install = root.resolve("Jagex");
        Path launcher = install.resolve(LAUNCHER);
        writePreferences(launcher, install);
        Path executable = clientExecutable(launcher);
        Files.createDirectories(install.resolve(GameCacheCandidates.DEFAULT_GAME_NAME));
        Files.createFile(install.resolve(GameCacheCandidates.DEFAULT_GAME_NAME)
                .resolve("ShaderManager.jcache"));

        assertTrue(RunningClientCache.forExecutable(executable).isEmpty(),
                "a directory that exists and holds only client settings is the decoy this "
                        + "whole predicate exists to walk past");
    }

    @Test
    void aCacheFolderThatNoLongerExistsResolvesNothing(@TempDir Path root) throws IOException {
        Path launcher = root.resolve(LAUNCHER);
        Path executable = clientExecutable(launcher);
        writePreferences(launcher, root.resolve("a-relocated-then-deleted-directory"));

        assertTrue(RunningClientCache.forExecutable(executable).isEmpty(),
                "cache_folder is a preference nothing revalidates, so it can outlive its "
                        + "directory; that is a fall-through, not a crash");
    }

    // ------------------------------------------------------- The pid hop

    @Test
    void thisJvmsOwnPidResolvesNoCacheWithoutThrowing() {
        long pid = ProcessHandle.current().pid();

        assertTrue(RunningClientCache.forPid(pid).isEmpty(),
                "the test JVM's executable has no client preferences.cfg above it; the point "
                        + "is that an unrelated process is declined quietly rather than "
                        + "throwing out of the host's startup");
    }

    @Test
    void aPidThatHasExitedResolvesNothing() throws Exception {
        // Re-launching this JVM's own executable keeps the case portable — CI runs
        // on Linux — and guarantees a pid that is genuinely gone by the assertion.
        String java = ProcessHandle.current().info().command().orElseThrow();
        Process process = new ProcessBuilder(java, "-version")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        long pid = process.pid();
        process.waitFor();

        assertTrue(RunningClientCache.forPid(pid).isEmpty(),
                "a client can exit between pipe discovery and the cache open, which is "
                        + "exactly why a candidate-list tier still sits behind this one");
    }

    @Test
    void anExecutableAtAFilesystemRootResolvesNothing() {
        Path root = Path.of(System.getProperty("user.dir")).getRoot();

        assertFalse(RunningClientCache.forExecutable(root).isPresent(),
                "a path with no parent must not throw while looking for a directory above it");
    }
}
