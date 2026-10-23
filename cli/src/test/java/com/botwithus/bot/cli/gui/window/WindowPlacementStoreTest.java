package com.botwithus.bot.cli.gui.window;

import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowPlacementStoreTest {

    private static final WindowPlacement ON_THE_LEFT =
            new WindowPlacement(new WindowRect(-1200, 40, 900, 650), true);

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

    @Test
    void load_nothingSaved_isEmpty() {
        assertEquals(Optional.empty(), new WindowPlacementStore(open()).load());
    }

    @Test
    void save_thenLoad_readsTheSamePlacement() {
        WindowPlacementStore store = new WindowPlacementStore(open());

        store.save(ON_THE_LEFT);

        assertEquals(Optional.of(ON_THE_LEFT), store.load());
    }

    @Test
    void save_writesTheWindowKeys() {
        HostSettings settings = open();

        new WindowPlacementStore(settings).save(ON_THE_LEFT);

        assertAll(
                () -> assertEquals(-1200L, settings.get(SettingKeys.WINDOW_X)),
                () -> assertEquals(40L, settings.get(SettingKeys.WINDOW_Y)),
                () -> assertEquals(900L, settings.get(SettingKeys.WINDOW_WIDTH)),
                () -> assertEquals(650L, settings.get(SettingKeys.WINDOW_HEIGHT)),
                () -> assertEquals(true, settings.get(SettingKeys.WINDOW_MAXIMISED)));
    }

    @Test
    void save_survivesARestart() {
        HostSettings first = open();
        new WindowPlacementStore(first).save(ON_THE_LEFT);
        first.flush();

        assertEquals(Optional.of(ON_THE_LEFT), new WindowPlacementStore(open()).load());
    }

    @Test
    void load_onlyPartOfTheBoundsSaved_isEmpty() {
        HostSettings settings = open();
        settings.set(SettingKeys.WINDOW_X, 10L);
        settings.set(SettingKeys.WINDOW_Y, 10L);
        settings.set(SettingKeys.WINDOW_WIDTH, 900L);
        settings.set(SettingKeys.WINDOW_MAXIMISED, true);

        assertEquals(Optional.empty(), new WindowPlacementStore(settings).load());
    }
}
