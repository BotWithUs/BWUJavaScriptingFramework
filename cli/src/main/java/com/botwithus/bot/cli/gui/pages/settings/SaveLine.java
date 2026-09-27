package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.settings.SaveStatus;

/**
 * The Settings header's word on whether the file on disk is up to date:
 * "saved", "saving …" or why the last write failed.
 */
public record SaveLine(State state, String text) {

    /** Which colour the header draws the line in. */
    public enum State { SAVED, SAVING, FAILED }

    public static SaveLine of(SaveStatus status) {
        return switch (status) {
            case SaveStatus.Saved _ -> new SaveLine(State.SAVED, "saved");
            case SaveStatus.Saving _ -> new SaveLine(State.SAVING, "saving …");
            case SaveStatus.Failed failed -> new SaveLine(State.FAILED, failed.message());
        };
    }
}
