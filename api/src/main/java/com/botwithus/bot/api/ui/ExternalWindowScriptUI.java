package com.botwithus.bot.api.ui;

/**
 * Marker for script UIs that manage their own window outside the host ImGui surface.
 *
 * <p>When a host detects this interface, it should avoid embedding the UI into its own
 * ImGui window and instead invoke {@link #render()} on a dedicated thread or external
 * window host as appropriate.</p>
 */
public interface ExternalWindowScriptUI extends ScriptUI {
}
