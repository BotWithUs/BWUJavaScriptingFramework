package com.botwithus.bot.cli.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsMigrationTest {

    private static final String LEGACY = """
            #JBotWithUs Auto-Start Settings
            autoConnect=false
            pipePrefix=FarmPipe
            scanIntervalMs=2000
            probeLobby=true
            """;

    @TempDir
    Path dir;

    /** Closed after each test so no debounced write races the temp folder's deletion. */
    private final List<HostSettings> opened = new ArrayList<>();

    @AfterEach
    void closeAll() {
        opened.forEach(HostSettings::close);
    }

    private HostSettings open() {
        HostSettings settings = HostSettings.open(dir);
        opened.add(settings);
        return settings;
    }

    private Path legacy() {
        return dir.resolve(SettingsMigration.LEGACY_FILE_NAME);
    }

    private Path backup() {
        return dir.resolve(SettingsMigration.LEGACY_FILE_NAME + SettingsMigration.BACKUP_SUFFIX);
    }

    @Test
    void run_noLegacyFile_doesNothing() {
        HostSettings settings = open();

        SettingsMigration.Result result = SettingsMigration.run(dir, settings);

        assertEquals(SettingsMigration.Outcome.NOTHING_TO_MIGRATE, result.outcome());
        assertFalse(Files.exists(dir.resolve(HostSettings.FILE_NAME)));
    }

    @Test
    void run_copiesTheGlobals_savesThem_andRenamesTheSourceToBak() throws IOException {
        Files.writeString(legacy(), LEGACY);
        HostSettings settings = open();

        SettingsMigration.Result result = SettingsMigration.run(dir, settings);

        assertEquals(SettingsMigration.Outcome.MIGRATED, result.outcome());
        assertEquals(List.of("autoConnect", "autoConnectPipes", "scanIntervalMs"), result.copiedKeys());
        HostSettings reread = open();
        assertFalse(reread.get(SettingKeys.AUTO_CONNECT));
        assertEquals("FarmPipe", reread.get(SettingKeys.PIPE_PREFIX));
        assertEquals(2_000L, reread.get(SettingKeys.SCAN_INTERVAL_MS));
        assertFalse(Files.exists(legacy()));
        assertEquals(LEGACY, Files.readString(backup()), "the backup is the untouched original");
        assertTrue(reread.unknownEntries().isEmpty(), "the dead probeLobby key is not carried over");
    }

    @Test
    void run_twice_secondRunIsANoOpAndKeepsLaterChanges() throws IOException {
        Files.writeString(legacy(), LEGACY);
        SettingsMigration.run(dir, open());

        HostSettings restarted = open();
        restarted.set(SettingKeys.SCAN_INTERVAL_MS, 9_000L);
        restarted.flush();
        SettingsMigration.Result second = SettingsMigration.run(dir, restarted);

        assertEquals(SettingsMigration.Outcome.NOTHING_TO_MIGRATE, second.outcome());
        HostSettings reread = open();
        assertEquals(9_000L, reread.get(SettingKeys.SCAN_INTERVAL_MS));
        assertEquals("FarmPipe", reread.get(SettingKeys.PIPE_PREFIX));
        assertEquals(LEGACY, Files.readString(backup()));
    }

    @Test
    void run_invalidLegacyValue_isSkippedAndTheRestMigrate() throws IOException {
        Files.writeString(legacy(), "scanIntervalMs=often\npipePrefix=Ok_Pipe\n");
        HostSettings settings = open();

        SettingsMigration.Result result = SettingsMigration.run(dir, settings);

        assertEquals(SettingsMigration.Outcome.MIGRATED, result.outcome());
        assertEquals(List.of("autoConnectPipes"), result.copiedKeys());
        assertEquals(5_000L, settings.get(SettingKeys.SCAN_INTERVAL_MS));
        assertEquals("Ok_Pipe", settings.get(SettingKeys.PIPE_PREFIX));
    }

    @Test
    void run_legacyAutoConnectThatIsNotTrue_meansOff_asTheOldReaderTreatedIt() throws IOException {
        Files.writeString(legacy(), "autoConnect=yes\n");
        HostSettings settings = open();

        SettingsMigration.run(dir, settings);

        assertFalse(settings.get(SettingKeys.AUTO_CONNECT));
    }

    @Test
    void run_settingsCannotBeSaved_leavesTheLegacyFileForTheNextStart() throws IOException {
        Files.writeString(legacy(), LEGACY);
        RecordingStorage storage = new RecordingStorage(dir.resolve(HostSettings.FILE_NAME));
        storage.failSaves(true);
        HostSettings settings = new HostSettings(storage, SettingKeys.ALL, () -> { }, Clock.systemUTC());
        opened.add(settings);

        SettingsMigration.Result result = SettingsMigration.run(dir, settings);

        assertEquals(SettingsMigration.Outcome.FAILED, result.outcome());
        assertTrue(Files.exists(legacy()));
        assertFalse(Files.exists(backup()));
    }
}
