package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.InvalidSettingException;
import com.botwithus.bot.cli.settings.SettingKeys;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The interact keys are saved with the host's settings and survive a restart. */
class InteractKeysStoreTest {

    @TempDir
    Path dir;

    @Test
    void savedKeys_areReadBackAfterARestart() {
        try (HostSettings settings = HostSettings.open(dir)) {
            new InteractKeysStore.Saved(settings).set(InteractKeys.parse("Ctrl+Shift").orElseThrow());
        }

        try (HostSettings again = HostSettings.open(dir)) {
            assertEquals("Ctrl+Shift", new InteractKeysStore.Saved(again).get().label());
        }
    }

    @Test
    void noSavedKeys_isCtrl_andTheSettingRefusesAKeyWithoutAModifier() {
        try (HostSettings settings = HostSettings.open(dir)) {
            assertAll(
                    () -> assertEquals(InteractKeys.DEFAULT, new InteractKeysStore.Saved(settings).get()),
                    () -> assertThrows(InvalidSettingException.class,
                            () -> settings.setText(SettingKeys.OVERLAY_INTERACT_KEYS.name(), "Q")));
        }
    }
}
