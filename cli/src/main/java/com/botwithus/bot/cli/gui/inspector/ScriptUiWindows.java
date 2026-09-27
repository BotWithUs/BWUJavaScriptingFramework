package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ClientScript;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ManagementScript;

import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;

/**
 * The scripts' own UIs that are popped out of the drawer, each in a top-level
 * ImGui window of its own: resizable, and, with viewports on, draggable out of
 * the host window as an OS window. Closing one with its X brings that UI back
 * into the drawer. Drawn every frame after the shell, whatever page or drawer
 * is showing. Render thread only.
 */
final class ScriptUiWindows {

    /**
     * The first-use size in body-font ems: 925 x 690 px at the default 17 px
     * body font and 100% scale, the size the pre-drawer script window opened at,
     * so it grows with DPI and the text size the way the rest of the UI does.
     */
    private static final float WIDTH_EM = 54.4f;
    private static final float HEIGHT_EM = 40.6f;
    /** Pivot for centring the first-use window on the host window. */
    private static final float CENTRE = 0.5f;

    private final Controls ui;
    private final InspectorState state;
    private final InspectorSource source;
    /** The X button's flag, reset before each window; ImGui clears it when the X is clicked. */
    private final ImBoolean isOpen = new ImBoolean();

    ScriptUiWindows(Controls ui, InspectorState state, InspectorSource source) {
        this.ui = ui;
        this.state = state;
        this.source = source;
    }

    void render() {
        for (InspectorTarget target : state.poppedOut(source)) {
            renderWindow(target);
        }
    }

    private void renderWindow(InspectorTarget target) {
        float fontSize = ui.m().fontSize();
        ImGuiViewport host = ImGui.getMainViewport();
        ImGui.setNextWindowPos(host.getPosX() + host.getSizeX() * CENTRE, host.getPosY() + host.getSizeY() * CENTRE,
                ImGuiCond.FirstUseEver, CENTRE, CENTRE);
        ImGui.setNextWindowSize(fontSize * WIDTH_EM, fontSize * HEIGHT_EM, ImGuiCond.FirstUseEver);
        isOpen.set(true);
        if (ImGui.begin(titleOf(target), isOpen, ImGuiWindowFlags.NoCollapse)) {
            ConfigInspector.drawScriptUi(target.customUi(), target.script().name());
        }
        ImGui.end();
        if (!isOpen.get()) {
            state.bringBack(target.subject());
        }
    }

    /**
     * The script's name, and for a client script which client it is on, so two
     * windows for the same script tell themselves apart. The id after
     * {@code ###} keeps the window's place and size when the title changes.
     */
    private static String titleOf(InspectorTarget target) {
        String shown = switch (target.subject()) {
            case ClientScript ignored -> target.script().name() + " " + target.context();
            case ManagementScript ignored -> target.script().name();
        };
        return shown + ScriptUiPopouts.imguiIdOf(target.subject());
    }
}
