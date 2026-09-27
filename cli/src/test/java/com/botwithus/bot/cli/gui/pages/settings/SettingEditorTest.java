package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SettingEditorTest {

    @TempDir
    Path dir;

    private HostSettings settings;
    private SettingEditor editor;

    @BeforeEach
    void setUp() {
        settings = HostSettings.open(dir);
        editor = new SettingEditor(settings);
    }

    @AfterEach
    void tearDown() {
        settings.close();
    }

    @Test
    void aValidRowEditIsAppliedInTheRowsUnit() {
        EditResult result = editor.editRow(SettingKeys.STALL_AFTER_MS.name(), "90");

        assertEquals(new EditResult.Applied(), result);
        assertEquals(90_000L, settings.get(SettingKeys.STALL_AFTER_MS));
    }

    @Test
    void textThatIsNotANumberIsRefusedWithTheRuleAndChangesNothing() {
        EditResult result = editor.editRow(SettingKeys.RPC_TIMEOUT_MS.name(), "abc");

        assertAll(
                () -> assertEquals(new EditResult.Refused(
                        "Must be a whole number from 100 to 600000, got 'abc'."), result),
                () -> assertEquals(10_000L, settings.get(SettingKeys.RPC_TIMEOUT_MS)),
                () -> assertFalse(settings.isExplicit(SettingKeys.RPC_TIMEOUT_MS)));
    }

    @Test
    void anOutOfRangeValueIsRefusedInTheUnitTheRowShows() {
        EditResult result = editor.editRow(SettingKeys.STALL_AFTER_MS.name(), "5");

        assertEquals(new EditResult.Refused("Must be from 10 to 3600 s."), result);
        assertEquals(SettingKeys.STALL_AFTER_MS.defaultValue(), settings.get(SettingKeys.STALL_AFTER_MS));
    }

    @Test
    void aScaledRowRefusesTextThatIsNotANumber() {
        EditResult result = editor.editRow(SettingKeys.SCAN_INTERVAL_MS.name(), "two");

        assertEquals(new EditResult.Refused("'two' is not a number."), result);
    }

    @Test
    void aRawEditUsesTheStoredFormAndNamesAnUnknownKey() {
        assertEquals(new EditResult.Applied(), editor.editRaw(SettingKeys.SCAN_INTERVAL_MS.name(), "2500"));
        assertEquals(2_500L, settings.get(SettingKeys.SCAN_INTERVAL_MS));
        assertEquals(new EditResult.Refused("'theme.accent' is not a known setting."),
                editor.editRaw("theme.accent", "#fff"));
    }
}
