package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.alerts.ServiceStatus;

import java.util.Objects;

/**
 * The state beside an Integrations service's name: a coloured dot and a word.
 *
 * @param label what it says, e.g. {@code Last send failed}
 * @param tone  which colour it is drawn in
 */
public record StatusChip(String label, Tone tone) {

    /** The chip's colour, by what it means rather than by hue. */
    public enum Tone {
        /** Delivered: the accent colour. */
        OK,
        /** The last send failed: the danger colour. */
        ERROR,
        /** Nothing to report yet, or switched off: the dim text colour. */
        IDLE,
        /** A test is on its way: the info colour. */
        BUSY
    }

    public StatusChip {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(tone, "tone");
    }

    /** The chip for {@code status}. */
    public static StatusChip of(ServiceStatus status) {
        Tone tone = switch (status) {
            case WORKING -> Tone.OK;
            case FAILED -> Tone.ERROR;
            case OFF, NOT_TESTED -> Tone.IDLE;
            case SENDING -> Tone.BUSY;
        };
        return new StatusChip(status.label(), tone);
    }
}
