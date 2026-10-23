package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.settings.SaveStatus;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SaveLineTest {

    @Test
    void eachSaveStatusHasItsHeaderLine() {
        assertAll(
                () -> assertEquals(new SaveLine(SaveLine.State.SAVED, "saved"),
                        SaveLine.of(new SaveStatus.Saved(Instant.EPOCH))),
                () -> assertEquals(new SaveLine(SaveLine.State.SAVING, "saving …"),
                        SaveLine.of(new SaveStatus.Saving())),
                () -> assertEquals(new SaveLine(SaveLine.State.FAILED, "Could not save settings: access denied"),
                        SaveLine.of(new SaveStatus.Failed("Could not save settings: access denied", Instant.EPOCH))));
    }
}
