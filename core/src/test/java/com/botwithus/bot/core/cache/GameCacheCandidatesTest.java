package com.botwithus.bot.core.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how an install is turned into cache-directory candidates.
 *
 * <p>Every case builds a fake install under a {@code @TempDir} and hands the
 * resolver an environment that points at it, so these run on any machine and
 * do not depend on a game being installed. The shapes themselves were taken
 * from two real installs — a Jagex-launcher one and a Steam one in a
 * non-default library.</p>
 */
class GameCacheCandidatesTest {

    private static final String PROGRAM_DATA = "ProgramData";
    private static final String PROGRAM_FILES_X86 = "ProgramFiles(x86)";

    private static UnaryOperator<String> environment(Map<String, String> variables) {
        return variables::get;
    }

    /** Writes a client preferences.cfg, with the real file's surrounding keys for realism. */
    private static void writePreferences(Path launcherDir, String cacheFolder) throws IOException {
        Files.createDirectories(launcherDir);
        Files.writeString(launcherDir.resolve("preferences.cfg"), String.join("\n",
                "graphics_device=2882",
                "confirm_quit=0",
                "cache_folder=" + cacheFolder,
                "Language=0",
                "user_folder=" + cacheFolder) + "\n");
    }

    // ------------------------------------------------------ preferences.cfg

    @Test
    void readsTheCacheFolderPreference(@TempDir Path root) throws IOException {
        writePreferences(root, "D:/Relocated/Jagex");

        assertEquals(Optional.of(Path.of("D:/Relocated/Jagex")),
                GameCacheCandidates.cacheFolder(root.resolve("preferences.cfg")));
    }

    @Test
    void anAbsentPreferencesFileIsNotAnError(@TempDir Path root) {
        assertEquals(Optional.empty(),
                GameCacheCandidates.cacheFolder(root.resolve("preferences.cfg")));
    }

    @Test
    void aPreferencesFileWithoutTheKeyIsEmpty(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("preferences.cfg"), "Language=0\nconfirm_quit=0\n");

        assertEquals(Optional.empty(),
                GameCacheCandidates.cacheFolder(root.resolve("preferences.cfg")));
    }

    @Test
    void aBlankCacheFolderValueIsEmpty(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("preferences.cfg"), "cache_folder=\n");

        assertEquals(Optional.empty(),
                GameCacheCandidates.cacheFolder(root.resolve("preferences.cfg")),
                "a half-written config must not produce a candidate of the empty path");
    }

    // -------------------------------------------------- The Jagex launcher

    @Test
    void aJagexInstallOffersItsPreferenceThenItsDefault(@TempDir Path root) throws IOException {
        Path programData = root.resolve("ProgramData");
        writePreferences(programData.resolve("Jagex").resolve("launcher"), "D:/Moved");

        List<Path> candidates = GameCacheCandidates.forEnvironment(
                environment(Map.of(PROGRAM_DATA, programData.toString())));

        assertEquals(List.of(
                Path.of("D:/Moved", "RuneScape"),
                programData.resolve("Jagex").resolve("RuneScape")), candidates,
                "a relocated cache must be tried before the default it no longer matches");
    }

    @Test
    void aJagexInstallWithNoPreferencesOffersOnlyItsDefault(@TempDir Path root) {
        Path programData = root.resolve("ProgramData");

        List<Path> candidates = GameCacheCandidates.forEnvironment(
                environment(Map.of(PROGRAM_DATA, programData.toString())));

        assertEquals(List.of(programData.resolve("Jagex").resolve("RuneScape")), candidates);
    }

    // ---------------------------------------------------------------- Steam

    @Test
    void steamLibrariesComeFromTheVdfAndNotOnlyTheDefaultRoot(@TempDir Path root)
            throws IOException {
        Path steam = root.resolve("Steam");
        Path elsewhere = root.resolve("SecondLibrary");
        Files.createDirectories(steam.resolve("steamapps"));
        Files.writeString(steam.resolve("steamapps").resolve("libraryfolders.vdf"),
                "\"libraryfolders\"\n{\n"
                        + "\t\"0\"\n\t{\n\t\t\"path\"\t\t\"" + vdfEscape(steam) + "\"\n\t}\n"
                        + "\t\"1\"\n\t{\n\t\t\"path\"\t\t\"" + vdfEscape(elsewhere) + "\"\n\t}\n}\n");

        List<Path> libraries = GameCacheCandidates.steamLibraries(
                environment(Map.of(PROGRAM_FILES_X86, root.toString())));

        assertEquals(List.of(steam, elsewhere), libraries,
                "the default root is listed in its own vdf, so it must appear exactly once");
    }

    @Test
    void aSteamInstallOffersTheDoubledGameNameDirectory(@TempDir Path root) throws IOException {
        Path steam = root.resolve("Steam");
        Path install = steam.resolve("steamapps").resolve("common").resolve("RuneScape");
        Files.createDirectories(install);

        List<Path> candidates = GameCacheCandidates.forEnvironment(
                environment(Map.of(PROGRAM_FILES_X86, root.toString())));

        assertEquals(List.of(install.resolve("RuneScape")), candidates,
                "the client writes <cache_folder>/<game name>, and for Steam the two nest");
    }

    @Test
    void aSteamInstallPrefersItsOwnPreferences(@TempDir Path root) throws IOException {
        Path steam = root.resolve("Steam");
        Path install = steam.resolve("steamapps").resolve("common").resolve("RuneScape");
        writePreferences(install.resolve("launcher"), install.toString());

        List<Path> candidates = GameCacheCandidates.forEnvironment(
                environment(Map.of(PROGRAM_FILES_X86, root.toString())));

        assertEquals(List.of(install.resolve("RuneScape")), candidates,
                "the preference names the same directory as the default here, so the "
                        + "duplicate must be collapsed rather than probed twice");
    }

    // ----------------------------------------------------- The environment

    @Test
    void bothInstallShapesAreOfferedWhenBothVariablesAreSet(@TempDir Path root) throws IOException {
        Path programData = root.resolve("ProgramData");
        Path steam = root.resolve("Steam");
        Files.createDirectories(steam);

        List<Path> candidates = GameCacheCandidates.forEnvironment(environment(Map.of(
                PROGRAM_DATA, programData.toString(),
                PROGRAM_FILES_X86, root.toString())));

        assertEquals(List.of(
                programData.resolve("Jagex").resolve("RuneScape"),
                steam.resolve("steamapps").resolve("common").resolve("RuneScape")
                        .resolve("RuneScape")), candidates,
                "the Jagex launcher is the common install, so it is probed first");
    }

    @Test
    void anEnvironmentWithNeitherVariableYieldsNoCandidates() {
        assertTrue(GameCacheCandidates.forEnvironment(name -> null).isEmpty(),
                "a non-Windows JVM must produce an empty list, not a bogus path");
    }

    @Test
    void aBlankVariableYieldsNoCandidate() {
        assertTrue(GameCacheCandidates.forEnvironment(
                environment(Map.of(PROGRAM_DATA, "   ", PROGRAM_FILES_X86, ""))).isEmpty());
    }

    private static String vdfEscape(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
