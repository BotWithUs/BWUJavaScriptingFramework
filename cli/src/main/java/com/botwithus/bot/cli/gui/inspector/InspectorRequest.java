package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ClientScript;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ManagementScript;
import com.botwithus.bot.core.runtime.ManagementScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRunner;

import java.util.Objects;

/**
 * A request to show the inspector on {@code subject}, opened on {@code tab}.
 * Built from a runner wherever a "Settings" button or command has one.
 */
public record InspectorRequest(InspectorSubject subject, InspectorTab tab) {

    public InspectorRequest {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(tab, "tab");
    }

    /** The inspector for a script on a game client, on the tab its settings suggest. */
    public static InspectorRequest forClientScript(ScriptRunner runner) {
        String name = runner.getScriptName();
        String client = Objects.requireNonNullElse(runner.getConnectionName(), "");
        boolean hasFields = !ScriptCode.fields(runner::getConfigFields, name).isEmpty();
        boolean hasUi = ScriptCode.ui(() -> runner.getScript().getUI(), name) != null;
        return new InspectorRequest(new ClientScript(client, name), InspectorTab.initialFor(hasFields, hasUi));
    }

    /** The inspector for a management script, on the tab its settings suggest. */
    public static InspectorRequest forManagementScript(ManagementScriptRunner runner) {
        String name = runner.getScriptName();
        boolean hasFields = !ScriptCode.fields(runner::getConfigFields, name).isEmpty();
        boolean hasUi = ScriptCode.ui(() -> runner.getScript().getUI(), name) != null;
        return new InspectorRequest(new ManagementScript(name), InspectorTab.initialFor(hasFields, hasUi));
    }
}
