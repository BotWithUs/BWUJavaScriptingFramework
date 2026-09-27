package com.botwithus.bot.cli.gui.pages.settings;

import java.util.Objects;

/**
 * The line beside Send test: how the last send to the service went, e.g.
 * {@code Test delivered 14:05 · 180 ms} or {@code 401 Unauthorized · Unknown Webhook (14:02)}.
 *
 * @param text already worded by the alert back end; never contains a secret
 * @param tone {@link StatusChip.Tone#OK} after a delivery, {@link StatusChip.Tone#ERROR} after a failure
 */
public record ResultLine(String text, StatusChip.Tone tone) {

    public ResultLine {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(tone, "tone");
    }
}
