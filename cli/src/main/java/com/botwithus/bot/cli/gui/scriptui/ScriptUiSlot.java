package com.botwithus.bot.cli.gui.scriptui;

import com.botwithus.bot.api.ui.ScriptUI;

import java.time.Instant;
import java.util.Objects;

/**
 * One script's UI as the host is about to draw it.
 *
 * @param owner     which running script this is: stable across frames and across
 *                  restarts of the same script on the same client, distinct per client
 * @param label     the script's name, for the render thread's name and the logs
 * @param ui        what the script's {@code getUI()} returned
 * @param startedAt when the script's current run started, or {@link #NEVER_STARTED}.
 *                  A change means the script was restarted, which clears its failures.
 */
public record ScriptUiSlot(String owner, String label, ScriptUI ui, Instant startedAt) {

    /** {@code startedAt} for a script that has not been started. */
    public static final Instant NEVER_STARTED = Instant.EPOCH;

    public ScriptUiSlot {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(ui, "ui");
        startedAt = startedAt == null ? NEVER_STARTED : startedAt;
    }
}
