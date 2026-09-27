package com.botwithus.bot.cli;

import com.botwithus.bot.cli.management.ManagementFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first management load pass after an upgrade is the one that gives the
 * scripts it finds the whole host; a script found by a later pass does not get it.
 */
class CliContextManagementTest {

    private static final String SCRIPTS_DIR_PROPERTY = "botwithus.scripts.dir";

    @TempDir
    Path dir;

    private String previousScriptsDir;

    @BeforeEach
    void useATemporaryScriptsFolder() {
        previousScriptsDir = System.getProperty(SCRIPTS_DIR_PROPERTY);
        System.setProperty(SCRIPTS_DIR_PROPERTY, dir.resolve("scripts").toString());
    }

    @AfterEach
    void restoreTheScriptsFolder() {
        if (previousScriptsDir == null) {
            System.clearProperty(SCRIPTS_DIR_PROPERTY);
        } else {
            System.setProperty(SCRIPTS_DIR_PROPERTY, previousScriptsDir);
        }
    }

    @Test
    void theFirstLoadPass_isTheMigration_soAScriptFoundLaterStartsNotApplied() {
        CliContext ctx = TestContexts.inDirInLine(dir, new PrintStream(OutputStream.nullOutputStream()));

        ctx.loadManagementScripts();
        ctx.getManagementTargets().recordLoaded(List.of("Newcomer"));

        assertAll(
                () -> assertTrue(Files.exists(dir.resolve(ManagementFile.FILE_NAME))),
                () -> assertTrue(ctx.getManagementTargets().isNotApplied("Newcomer")));
    }
}
