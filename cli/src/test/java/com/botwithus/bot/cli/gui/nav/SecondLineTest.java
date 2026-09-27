package com.botwithus.bot.cli.gui.nav;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SecondLineTest {

    @TempDir
    Path root;

    private Path cwd() {
        return root.resolve("host");
    }

    private Path home() {
        return root.resolve("home");
    }

    @Test
    void aFolderInsideTheWorkingDirectory_isShownRelativeToIt() {
        assertEquals("scripts/", SecondLine.FolderPath.of(cwd().resolve("scripts"), cwd(), home()).text());
    }

    @Test
    void aNestedFolder_usesForwardSlashes() {
        Path management = cwd().resolve("scripts").resolve("management");

        assertEquals("scripts/management/", SecondLine.FolderPath.of(management, cwd(), home()).text());
    }

    @Test
    void theUserFallbackFolder_isShownFromHome() {
        Path fallback = home().resolve(".botwithus").resolve("scripts");

        assertEquals("~/.botwithus/scripts/", SecondLine.FolderPath.of(fallback, cwd(), home()).text());
    }

    @Test
    void aFolderElsewhere_isShownInFull() {
        Path elsewhere = root.resolve("shared").resolve("scripts");
        String expected = elsewhere.toAbsolutePath().toString().replace('\\', '/') + "/";

        assertEquals(expected, SecondLine.FolderPath.of(elsewhere, cwd(), home()).text());
    }

    @Test
    void aRelativeFolder_isResolvedAgainstTheProcessWorkingDirectory() {
        Path processCwd = Path.of("").toAbsolutePath();

        assertEquals("scripts/", SecondLine.FolderPath.of(Path.of("scripts"), processCwd, home()).text());
    }
}
