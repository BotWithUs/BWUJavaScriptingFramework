package com.botwithus.bot.cli.gui.scriptui;

import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.api.ui.ScriptUiHost;

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
 * @param host      what {@code ui} is attached to before each frame, inside the sandbox
 */
public record ScriptUiSlot(String owner, String label, ScriptUI ui, Instant startedAt, ScriptUiHost host) {

    /** {@code startedAt} for a script that has not been started. */
    public static final Instant NEVER_STARTED = Instant.EPOCH;

    public ScriptUiSlot {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(ui, "ui");
        Objects.requireNonNull(host, "host");
        startedAt = startedAt == null ? NEVER_STARTED : startedAt;
    }

    /** A UI drawn in the drawer, attached to {@link ScriptUiHandle#DOCKED}. */
    public ScriptUiSlot(String owner, String label, ScriptUI ui, Instant startedAt) {
        this(owner, label, ui, startedAt, ScriptUiHandle.DOCKED);
    }
}
