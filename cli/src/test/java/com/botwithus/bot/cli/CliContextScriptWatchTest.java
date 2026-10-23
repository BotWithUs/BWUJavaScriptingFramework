package com.botwithus.bot.cli;

import com.botwithus.bot.cli.command.CommandParser;
import com.botwithus.bot.cli.command.impl.ReloadCommand;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The folder watch follows the {@code autoReload} setting live, and
 * {@code reload --watch} is that same setting.
 */
class CliContextScriptWatchTest {

    private static final String SCRIPTS_DIR_PROPERTY = "botwithus.scripts.dir";

    @TempDir
    Path tmp;

    private String previousScriptsDir;
    private CliContext ctx;
    private HostSettings settings;

    @BeforeEach
    void setUp() {
        // Watch a temporary folder, never the real scripts folder.
        previousScriptsDir = System.getProperty(SCRIPTS_DIR_PROPERTY);
        System.setProperty(SCRIPTS_DIR_PROPERTY, tmp.resolve("scripts").toString());
        PrintStream ps = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        LogBuffer logBuffer = new LogBuffer();
        ctx = new CliContext(logBuffer, new LogCapture(logBuffer, ps, ps), tmp.resolve("groups.json"));
        settings = HostSettings.open(tmp.resolve("settings"));
    }

    @AfterEach
    void tearDown() {
        ctx.stopScriptWatcher();
        settings.close();
        if (previousScriptsDir == null) {
            System.clearProperty(SCRIPTS_DIR_PROPERTY);
        } else {
            System.setProperty(SCRIPTS_DIR_PROPERTY, previousScriptsDir);
        }
    }

    @Test
    void turningTheSettingOnAndOff_startsAndStopsTheWatch() {
        ctx.setSettings(settings);
        assertFalse(ctx.isWatcherRunning(), "off by default");

        settings.set(SettingKeys.AUTO_RELOAD, true);
        assertTrue(ctx.isWatcherRunning());

        settings.set(SettingKeys.AUTO_RELOAD, false);
        assertFalse(ctx.isWatcherRunning());
    }

    @Test
    void aSettingAlreadyOnAtStartUp_startsTheWatch() {
        settings.set(SettingKeys.AUTO_RELOAD, true);

        ctx.setSettings(settings);

        assertTrue(ctx.isWatcherRunning());
    }

    @Test
    void reloadWatch_flipsTheSetting_soItPersistsAndTheSettingsPageAgrees() {
        ctx.setSettings(settings);
        ReloadCommand reload = new ReloadCommand();

        reload.execute(CommandParser.parse("reload --watch"), ctx);
        assertTrue(ctx.isWatcherRunning());
        assertTrue(settings.get(SettingKeys.AUTO_RELOAD));

        reload.execute(CommandParser.parse("reload --watch"), ctx);
        assertFalse(ctx.isWatcherRunning());
        assertFalse(settings.get(SettingKeys.AUTO_RELOAD));
    }

    @Test
    void withNoSettings_reloadWatchStillWorks() {
        new ReloadCommand().execute(CommandParser.parse("reload --watch"), ctx);

        assertTrue(ctx.isWatcherRunning());
    }
}
