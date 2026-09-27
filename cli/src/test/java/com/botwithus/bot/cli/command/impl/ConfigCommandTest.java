package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.command.CommandParser;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigCommandTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private CliContext ctx;
    private HostSettings settings;
    private ConfigCommand command;

    @BeforeEach
    void setUp() {
        PrintStream ps = new PrintStream(output);
        ctx = new CliContext(new LogBuffer(), new LogCapture(new LogBuffer(), ps, ps));
        settings = HostSettings.open(dir);
        command = new ConfigCommand(settings);
    }

    @AfterEach
    void tearDown() {
        settings.close();
    }

    private String run(String line) {
        output.reset();
        command.execute(CommandParser.parse(line), ctx);
        return output.toString();
    }

    @Test
    void set_savesImmediatelyWithoutConfigSave() {
        String out = run("config set scanIntervalMs 2000");

        assertTrue(out.contains("Set scanIntervalMs = 2000"), out);
        assertTrue(out.contains("Saved to"), out);
        assertEquals(2_000L, HostSettings.open(dir).get(SettingKeys.SCAN_INTERVAL_MS),
                "a fresh reader sees the value as soon as the command returns");
    }

    @Test
    void set_badValue_isRefusedWithTheRuleAndChangesNothing() {
        String out = run("config set scanIntervalMs 1");

        assertTrue(out.contains("Not set: scanIntervalMs must be a whole number from 500 to 600000, got 1"), out);
        assertEquals(5_000L, settings.get(SettingKeys.SCAN_INTERVAL_MS));
        assertFalse(Files.exists(dir.resolve(HostSettings.FILE_NAME)));
    }

    @Test
    void set_unknownKey_isRefused() {
        String out = run("config set colour green");

        assertTrue(out.contains("Not set: colour is not a known setting"), out);
    }

    @Test
    void save_isStillAccepted() {
        run("config set autoReload true");

        String out = run("config save");

        assertTrue(out.contains("Saved to"), out);
        assertFalse(out.contains("Unknown subcommand"), out);
    }

    @Test
    void reset_restoresTheDefault() {
        run("config set scanIntervalMs 2000");

        String out = run("config reset scanIntervalMs");

        assertTrue(out.contains("Reset scanIntervalMs to its default, 5000"), out);
        assertFalse(HostSettings.open(dir).isExplicit(SettingKeys.SCAN_INTERVAL_MS));
    }

    @Test
    void show_listsEveryKey_marksDefaults_andShowsForeignEntries() throws IOException {
        Files.writeString(dir.resolve(HostSettings.FILE_NAME), "autoReload=true\ntheme.accent=#4ade80\n");
        command = new ConfigCommand(HostSettings.open(dir));

        String out = run("config");

        assertTrue(out.contains("autoReload = true\n") || out.contains("autoReload = true\r\n"), out);
        assertTrue(out.contains("scanIntervalMs = 5000  (default)"), out);
        assertTrue(out.contains("ui.startMode = NORMAL  (default)"), out);
        assertTrue(out.contains("theme.accent = #4ade80"), out);
    }
}
