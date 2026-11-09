package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Settings page in a developer run: the Auto-connect row says the run keeps
 * it off, while the switch still shows (and edits) the saved setting.
 */
class LiveSettingsModelRunNoteTest {

    private static final int WINDOWS_PERCENT = 100;

    @TempDir
    Path home;

    private HostSettings settings;

    @BeforeEach
    void setUp() throws IOException {
        settings = HostSettings.open(Files.createDirectories(home.resolve(".botwithus")));
    }

    @AfterEach
    void tearDown() {
        settings.close();
    }

    @Test
    void devRun_notesTheAutoConnectRow_andNoOther() {
        Map<String, Optional<String>> notes = runNotesByRow(() -> true);

        assertEquals(Optional.of(LiveSettingsModel.DEV_RUN_AUTO_CONNECT), notes.get(SettingKeys.AUTO_CONNECT.name()));
        assertTrue(LiveSettingsModel.DEV_RUN_AUTO_CONNECT.startsWith("In this run: off."),
                "the note must read as a note without its colour");
        notes.forEach((name, note) -> {
            if (!name.equals(SettingKeys.AUTO_CONNECT.name())) {
                assertEquals(Optional.empty(), note, name);
            }
        });
    }

    @Test
    void normalRun_notesNoRow() {
        runNotesByRow(() -> false).forEach((name, note) -> assertEquals(Optional.empty(), note, name));
    }

    @Test
    void defaultHost_isANormalRun() {
        LiveSettingsModel.Host host = new LiveSettingsModel.Host(settings, Optional.empty(), List::of);

        assertFalse(host.autoConnectForcedOff().getAsBoolean());
    }

    private Map<String, Optional<String>> runNotesByRow(BooleanSupplier forcedOff) {
        LiveSettingsModel.Host host = new LiveSettingsModel.Host(settings, Optional.empty(), List::of,
                Optional::empty, MicrophoneDecisions.none(), forcedOff);
        LiveSettingsModel.Places places = new LiveSettingsModel.Places(home, home, home, home, home);
        LiveSettingsModel model = new LiveSettingsModel(host, places, path -> { }, Runnable::run,
                Clock.systemUTC(), () -> WINDOWS_PERCENT);
        return model.view().sections().stream()
                .flatMap(section -> section.items().stream())
                .flatMap(LiveSettingsModelRunNoteTest::keyRow)
                .collect(Collectors.toMap(SettingsItem.KeyRow::name, SettingsItem.KeyRow::runNote, (a, b) -> a));
    }

    private static Stream<SettingsItem.KeyRow> keyRow(SettingsItem item) {
        return switch (item) {
            case SettingsItem.KeyRow row -> Stream.of(row);
            case SettingsItem.Accounts _, SettingsItem.WaitPreview _, SettingsItem.PlaceRow _,
                 SettingsItem.ActionRow _, SettingsItem.RawKeys _, SettingsItem.ServiceCard _,
                 SettingsItem.EventGrid _, SettingsItem.QuietHours _, SettingsItem.Notice _,
                 SettingsItem.Microphone _ -> Stream.empty();
        };
    }
}
