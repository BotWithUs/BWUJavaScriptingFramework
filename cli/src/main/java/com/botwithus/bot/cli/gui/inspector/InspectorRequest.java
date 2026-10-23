package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ClientScript;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ManagementScript;
import com.botwithus.bot.cli.management.Target;
import com.botwithus.bot.core.runtime.ManagementScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRunner;

import java.util.Objects;
import java.util.Optional;

/**
 * A request to show {@code subject}: the inspector drawer opened on
 * {@code drawer}'s tab, the script's own UI popped out into a window of its
 * own, or both. Built from a runner wherever a "Settings" button or command has one.
 *
 * @param drawer    the tab to open the drawer on; empty leaves the drawer as it is
 * @param popsOutUi whether the script's own UI opens in its own window
 */
public record InspectorRequest(InspectorSubject subject, Optional<InspectorTab> drawer, boolean popsOutUi) {

    public InspectorRequest {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(drawer, "drawer");
        if (drawer.isEmpty() && !popsOutUi) {
            throw new IllegalArgumentException("a request must open the drawer, pop out the UI, or both");
        }
    }

    /** The drawer on {@code tab}, and nothing popped out. */
    public InspectorRequest(InspectorSubject subject, InspectorTab tab) {
        this(subject, Optional.of(Objects.requireNonNull(tab, "tab")), false);
    }

    /**
     * What a "Settings" button shows. A script that draws its own UI gets it in
     * a window of its own, sized for the room such a UI is laid out for; the
     * drawer opens on the fields beside it only when there are fields to show.
     * A script with no UI opens the drawer on its fields, or on the line that
     * says it has none.
     */
    public static InspectorRequest settings(InspectorSubject subject, boolean hasFields, boolean hasCustomUi) {
        if (!hasCustomUi) {
            return new InspectorRequest(subject, InspectorTab.SETTINGS);
        }
        Optional<InspectorTab> drawer = hasFields ? Optional.of(InspectorTab.SETTINGS) : Optional.empty();
        return new InspectorRequest(subject, drawer, true);
    }

    /** The script's own UI in its own window, leaving the drawer alone. */
    public static InspectorRequest scriptUi(InspectorSubject subject) {
        return new InspectorRequest(subject, Optional.empty(), true);
    }

    /** What "Settings" on a script on a game client shows. */
    public static InspectorRequest forClientScript(ScriptRunner runner) {
        String name = runner.getScriptName();
        String client = Objects.requireNonNullElse(runner.getConnectionName(), "");
        boolean hasFields = !ScriptCode.fields(runner::getConfigFields, name).isEmpty();
        boolean hasUi = ScriptCode.ui(() -> runner.getScript().getUI(), name) != null;
        return settings(new ClientScript(client, name), hasFields, hasUi);
    }

    /** What "Settings" on a management script shows. */
    public static InspectorRequest forManagementScript(ManagementScriptRunner runner) {
        String name = runner.getScriptName();
        boolean hasFields = !ScriptCode.fields(runner::getConfigFields, name).isEmpty();
        boolean hasUi = ScriptCode.ui(() -> runner.getScript().getUI(), name) != null;
        return settings(new ManagementScript(name), hasFields, hasUi);
    }

    /**
     * The inspector on one target's own settings for a management script, as a
     * target's settings button opens it. The form falls back to the defaults if
     * {@code target} is not one the picker offers.
     */
    public static InspectorRequest forManagementTarget(ManagementScriptRunner runner, Target target) {
        Objects.requireNonNull(target, "target");
        return new InspectorRequest(new ManagementScript(runner.getScriptName(), Optional.of(target)),
                InspectorTab.SETTINGS);
    }
}
