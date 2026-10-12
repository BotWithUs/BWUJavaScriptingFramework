package com.botwithus.bot.cli.gui.scriptui;

import imgui.internal.ImGui;

/**
 * Keeps the host aware that a script UI is typing. Before sandboxing, a
 * script's active text field was an active item in the host's own context, so
 * host shortcuts that skip themselves while {@code ImGui.isAnyItemActive()}
 * (Esc closing the drawer, for one) left the keystroke to the field. The field
 * now lives in the sandbox, so the host is told by making the panel's own
 * input item active instead. Its own class because it needs Dear ImGui's
 * internal API, whose class name clashes with the public one.
 */
final class HostFocus {

    private HostFocus() {
    }

    /** Makes item {@code itemId}, in the host's current window, the active item. */
    static void hold(int itemId) {
        ImGui.setActiveID(itemId, ImGui.getCurrentWindow());
    }
}
