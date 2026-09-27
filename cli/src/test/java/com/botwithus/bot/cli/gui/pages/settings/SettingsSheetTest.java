package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SaveStatus;
import com.botwithus.bot.cli.settings.SettingKey;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.cli.settings.StartMode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsSheetTest {

    private static final int WINDOWS_PERCENT = 150;

    @TempDir
    Path dir;

    private HostSettings settings;

    @BeforeEach
    void setUp() {
        settings = HostSettings.open(dir);
    }

    @AfterEach
    void tearDown() {
        settings.close();
    }

    private RowControl control(SettingKey<?> key) {
        Placement placement = SettingsLayout.find(key.name()).orElseThrow();
        return SettingsSheet.controlFor(key, settings.text(key), placement.unit(), WINDOWS_PERCENT);
    }

    private static SettingsSheet.Inputs inputs() {
        return new SettingsSheet.Inputs(List.of(), "~/.botwithus/", "scripts/", "~/.botwithus/config.properties",
                WINDOWS_PERCENT, Optional.empty());
    }

    private SettingsView view() {
        return SettingsSheet.build(settings, settings.status(), inputs());
    }

    private static List<RowControl.Option> options(String... valueThenLabel) {
        List<RowControl.Option> options = new ArrayList<>();
        for (int i = 0; i < valueThenLabel.length; i += 2) {
            options.add(new RowControl.Option(valueThenLabel[i], valueThenLabel[i + 1]));
        }
        return options;
    }

    @Test
    void controlFor_picksOneControlPerType() {
        assertAll(
                () -> assertEquals(new RowControl.Switch(true), control(SettingKeys.AUTO_CONNECT)),
                () -> assertEquals(new RowControl.NumberBox("10000", "ms"), control(SettingKeys.RPC_TIMEOUT_MS)),
                () -> assertEquals(new RowControl.NumberBox("2", "×"), control(SettingKeys.RECONNECT_BACKOFF)),
                () -> assertEquals(new RowControl.TextField("BotWithUs"), control(SettingKeys.PIPE_PREFIX)),
                () -> assertEquals(new RowControl.Segments(options("NORMAL", "Normal", "ADVANCED", "Advanced"), 0),
                        control(SettingKeys.START_MODE)),
                () -> assertEquals(new RowControl.Dropdown(options("MATCH_WINDOWS", "Match Windows (150%)",
                        "PERCENT_100", "100%", "PERCENT_125", "125%", "PERCENT_150", "150%",
                        "PERCENT_175", "175%"), 0), control(SettingKeys.TEXT_SIZE)));
    }

    /** The frameless window's opt-out is a row, not only a line in the key table. */
    @Test
    void layout_offersTheWindowsTitleBarAsAnInterfaceSwitch() {
        Placement placement = SettingsLayout.find(SettingKeys.NATIVE_FRAME.name()).orElseThrow();

        assertAll(
                () -> assertEquals(SettingsSection.INTERFACE, placement.section()),
                () -> assertEquals(new RowControl.Switch(false), control(SettingKeys.NATIVE_FRAME)));
    }

    @Test
    void controlFor_showsAMillisecondSettingInSecondsWhenItsRowSaysSo() {
        settings.set(SettingKeys.STALL_AFTER_MS, 90_500L);

        assertEquals(new RowControl.NumberBox("90.5", "s"), control(SettingKeys.STALL_AFTER_MS));
    }

    @Test
    void controlFor_marksTheStoredChoice() {
        settings.set(SettingKeys.START_MODE, StartMode.ADVANCED);

        RowControl control = control(SettingKeys.START_MODE);

        assertEquals(1, switch (control) {
            case RowControl.Segments segments -> segments.selected();
            default -> -1;
        });
    }

    @Test
    void build_listsEveryKnownKeyAndKeepsUnknownOnesReadOnly() throws IOException {
        settings.close();
        Files.writeString(dir.resolve(HostSettings.FILE_NAME), "theme.accent=#4ade80\ndefaultTimeout=2500\n");
        settings = HostSettings.open(dir);

        List<RawKeyRow> rows = rawRows(view());

        assertEquals(settings.keys().size() + 1, rows.size());
        RawKeyRow timeout = row(rows, "defaultTimeout");
        RawKeyRow unknown = row(rows, "theme.accent");
        assertAll(
                () -> assertEquals("2500", timeout.value()),
                () -> assertEquals("RPC timeout", timeout.shownAs()),
                () -> assertTrue(timeout.isEditable()),
                () -> assertTrue(timeout.isExplicit()),
                () -> assertEquals("#4ade80", unknown.value()),
                () -> assertFalse(unknown.isEditable()));
    }

    @Test
    void build_keepsTheSectionsInPageOrderWithEveryPlacedRow() {
        SettingsView view = view();

        assertEquals(List.of(SettingsSection.values()), view.sections().stream().map(SectionView::section).toList());
        long keyRows = view.sections().stream().flatMap(s -> s.items().stream())
                .filter(i -> switch (i) {
                    case SettingsItem.KeyRow _ -> true;
                    default -> false;
                }).count();
        assertEquals(SettingsLayout.placements().size(), keyRows);
    }

    @Test
    void build_reportsTheSaveStatusInTheHeader() {
        SettingsView failed = SettingsSheet.build(settings,
                new SaveStatus.Failed("Could not save settings: disk full", Instant.EPOCH), inputs());

        assertEquals(new SaveLine(SaveLine.State.FAILED, "Could not save settings: disk full"), failed.save());
    }

    private static List<RawKeyRow> rawRows(SettingsView view) {
        return view.sections().stream().flatMap(s -> s.items().stream())
                .flatMap(i -> switch (i) {
                    case SettingsItem.RawKeys keys -> keys.rows().stream();
                    default -> Stream.<RawKeyRow>empty();
                }).toList();
    }

    private static RawKeyRow row(List<RawKeyRow> rows, String name) {
        return rows.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }
}
