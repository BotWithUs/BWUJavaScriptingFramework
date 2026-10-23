package com.botwithus.bot.cli.management;

import com.botwithus.bot.cli.management.ManagementFile.Contents;
import com.botwithus.bot.cli.management.ManagementFile.Stored;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code management.json} read back as it was written. */
class ManagementFileTest {

    private static final Stored BREAKS = new Stored(false,
            List.of(new Target.ClientScript("0123456789abcdef0123456789abcdef", "Woodcutting")), true);
    private static final Stored MONITOR = new Stored(true, List.of(), false);
    private static final String NEWER_HOST = "{\"version\": 99, \"scripts\": {}}";

    @TempDir
    Path dir;

    private ManagementFile file() {
        return new ManagementFile(dir.resolve(ManagementFile.FILE_NAME));
    }

    @Test
    void whatIsWritten_isReadBack() throws Exception {
        file().write(Map.of("Break Scheduler", BREAKS, "Fleet Monitor", MONITOR), true);

        assertEquals(new Contents.Current(Map.of("Break Scheduler", BREAKS, "Fleet Monitor", MONITOR), true),
                file().read());
    }

    @Test
    void aScriptWithNothingToKeep_isLeftOut() throws Exception {
        file().write(Map.of("Idle", Stored.NONE, "Fleet Monitor", MONITOR), false);

        assertEquals(new Contents.Current(Map.of("Fleet Monitor", MONITOR), false), file().read());
    }

    @Test
    void noFile_readsAsMissing() throws Exception {
        assertEquals(new Contents.Missing(), file().read());
    }

    @Test
    void aFileFromANewerHost_isSetAside_andReadsAsMissing() throws Exception {
        Files.writeString(dir.resolve(ManagementFile.FILE_NAME), NEWER_HOST);

        Contents contents = file().read();

        assertAll(
                () -> assertEquals(new Contents.Missing(), contents),
                () -> assertTrue(Files.exists(dir.resolve(ManagementFile.FILE_NAME + ".unsupported"))));
    }
}
