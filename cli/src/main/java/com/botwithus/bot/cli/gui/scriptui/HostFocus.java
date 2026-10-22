package com.botwithus.bot.cli.gui.scriptui;

import imgui.internal.ImGui;
import imgui.internal.ImGuiWindow;

/**
 * Keeps the host aware that a script UI is typing. Before sandboxing, a
 * script's active text field was an active item in the host's own context, so
 * host shortcuts that skip themselves while {@code ImGui.isAnyItemActive()}
 * left the keystroke to the field. The field now lives in the sandbox, so the
 * host is told by making the panel's own input item active instead.
 *
 * <p>The hold lasts one frame: from where the panel is drawn to
 * {@link ScriptUiRenderer#endFrame}, which lets it go. An active item is global
 * to the host's context, so a hold carried into the next frame would take every
 * press there from every other item, the window's drag strip and Dear ImGui's
 * click-to-focus included. Anything that runs before the panel asks
 * {@link ScriptUiRenderer#hasKeyboard} instead. Nor is anything held in a frame
 * whose press is not the panel's ({@link PanelInput#isPressElsewhere}), or the
 * hold would take that press from the host items drawn after the panel.</p>
 *
 * <p>Its own class because it needs Dear ImGui's internal API, whose class name
 * clashes with the public one.</p>
 */
final class HostFocus {

    /** Dear ImGui's "no window": focusing it leaves no window focused. */
    private static final ImGuiWindow NO_WINDOW = new ImGuiWindow(0L);

    private HostFocus() {
    }

    /** Makes item {@code itemId}, in the host's current window, the active item. */
    static void hold(int itemId) {
        ImGui.setActiveID(itemId, ImGui.getCurrentWindow());
    }

    /** Lets go of a {@link #hold}, unless something else became the active item since. */
    static void release(int itemId) {
        if (ImGui.getActiveID() == itemId) {
            ImGui.clearActiveID();
        }
    }

    /**
     * In the current context, a sandbox's mid-frame: no window has focus, as
     * after a click on no window. An active text field is deactivated.
     */
    static void dropAll() {
        ImGui.focusWindow(NO_WINDOW);
    }
}
