package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.FramelessChrome;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ClientScript;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ManagementScript;
import com.botwithus.bot.cli.gui.scriptui.BackgroundDrag;
import com.botwithus.bot.cli.gui.scriptui.BorderResize;
import com.botwithus.bot.cli.gui.scriptui.ScriptUiHandle;
import com.botwithus.bot.cli.gui.scriptui.ScriptUiRenderer;
import com.botwithus.bot.cli.gui.scriptui.ScriptUiSlot;

import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.ImGuiWindowClass;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiViewportFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;

import org.lwjgl.glfw.GLFW;

/**
 * The scripts' own UIs that are popped out of the drawer, each in an OS window
 * of its own: resizable, movable anywhere on the desktop, and never folded back
 * into the host window just because it overlaps it. Closing one with its X
 * brings that UI back into the drawer. Drawn every frame after the shell,
 * whatever page or drawer is showing. Render thread only.
 *
 * <p>A UI that draws its own chrome gets a frameless window instead: no title
 * bar, X, padding, border, background or visible resize grip, only what the
 * script draws, and its OS window is transparent wherever the script draws
 * nothing. It closes through its {@code ScriptUiHost}, moves by dragging any
 * spot the script leaves free of widgets, and resizes from its edges. The UI
 * is sandboxed like any other ({@link ScriptUiRenderer}), so the script never
 * moves or sizes the window itself: the sandbox reports a press on no widget
 * of the script's, and {@link BackgroundDrag} moves the window from the host's
 * side, while {@link BorderResize} gives it a resize border along its edges
 * like the frameless main window's.
 * A UI that is stopped, or threw last frame, gets the framed window back, so
 * it can still be closed.</p>
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
    /**
     * The smallest a frameless window can be resized to, in body-font ems:
     * enough to keep a script's own title bar and close button in reach.
     */
    private static final float MIN_WIDTH_EM = 12f;
    private static final float MIN_HEIGHT_EM = 6f;
    /**
     * A frameless window: the script draws everything, scrolling included, and
     * {@link BorderResize} resizes it, so Dear ImGui's own border and grip are off.
     */
    private static final int FRAMELESS_FLAGS = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoBackground
            | ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse
            | ImGuiWindowFlags.NoResize;
    /** The style vars {@link #pushFramelessStyle()} pushes: padding, border size, rounding. */
    private static final int FRAMELESS_VARS = 3;

    private final Controls ui;
    private final InspectorState state;
    private final InspectorSource source;
    private final ScriptUiRenderer scriptUis;
    /** The X button's flag, reset before each window; ImGui clears it when the X is clicked. */
    private final ImBoolean isOpen = new ImBoolean();
    /**
     * The window class every pop-out is given. NoAutoMerge keeps each one in a
     * viewport, so an OS window, of its own: without it ImGui merges a window
     * that lies inside the host window into the host's viewport, and a pop-out
     * opens centred on the host, so it stayed drawn inside the host window and
     * could not leave it while the host was maximised. Built once and reused
     * every frame; it lives as long as the GUI.
     */
    private final ImGuiWindowClass ownViewport = new ImGuiWindowClass();
    /** Moves a frameless pop-out whose background is dragged. */
    private final BackgroundDrag drag = new BackgroundDrag();
    /** Resizes a frameless pop-out from its edges. */
    private final BorderResize resize = new BorderResize();
    /** Set this frame when a frameless pop-out's OS window is still to be created. */
    private boolean isTransparentWindowDue;

    ScriptUiWindows(Controls ui, InspectorState state, InspectorSource source, ScriptUiRenderer scriptUis) {
        this.ui = ui;
        this.state = state;
        this.source = source;
        this.scriptUis = scriptUis;
        ownViewport.setViewportFlagsOverrideSet(ImGuiViewportFlags.NoAutoMerge);
    }

    /** Draws every popped-out script UI in its own window, in the order they were popped out. */
    void render() {
        isTransparentWindowDue = false;
        for (InspectorTarget target : state.poppedOut(source)) {
            renderWindow(target);
        }
        requestTransparentWindows(isTransparentWindowDue);
    }

    /** One pop-out: its own OS window, which brings the UI back into the drawer when it is closed. */
    private void renderWindow(InspectorTarget target) {
        ScriptUiSlot slot = target.scriptUiSlot(new ScriptUiHandle(() -> state.bringBack(target.subject()), true));
        boolean isFrameless = scriptUis.drawsOwnChrome(slot);
        float fontSize = ui.m().fontSize();
        ImGuiViewport host = ImGui.getMainViewport();
        ImGui.setNextWindowPos(host.getPosX() + host.getSizeX() * CENTRE, host.getPosY() + host.getSizeY() * CENTRE,
                ImGuiCond.FirstUseEver, CENTRE, CENTRE);
        ImGui.setNextWindowSize(fontSize * WIDTH_EM, fontSize * HEIGHT_EM, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowClass(ownViewport);
        if (isFrameless) {
            renderFrameless(target, slot);
        } else {
            renderFramed(target, slot);
        }
    }

    /** The host's window around the UI: title bar, X, padding and background. */
    private void renderFramed(InspectorTarget target, ScriptUiSlot slot) {
        isOpen.set(true);
        if (ImGui.begin(titleOf(target), isOpen, ImGuiWindowFlags.NoCollapse)) {
            scriptUis.draw(slot);
        }
        ImGui.end();
        if (!isOpen.get()) {
            state.bringBack(target.subject());
        }
    }

    /**
     * Only the UI. The frameless style is popped again straight after
     * {@code begin}, so the popups and child windows the script opens keep the
     * host's look.
     */
    private void renderFrameless(InspectorTarget target, ScriptUiSlot slot) {
        String title = titleOf(target);
        float fontSize = ui.m().fontSize();
        drag.beforeBegin(title);
        resize.beforeBegin(title, Math.round(fontSize * MIN_WIDTH_EM), Math.round(fontSize * MIN_HEIGHT_EM));
        pushFramelessStyle();
        boolean isVisible = ImGui.begin(title, FRAMELESS_FLAGS);
        ImGui.popStyleVar(FRAMELESS_VARS);
        if (!ImGui.getWindowViewport().getPlatformWindowCreated()) {
            isTransparentWindowDue = true;
        }
        if (isVisible) {
            boolean isPointerTaken = resize.takesPointer(title, FramelessChrome.resizeBorder(ui.m()));
            drag.afterDraw(title, scriptUis.draw(slot, isPointerTaken));
        }
        ImGui.end();
    }

    /** No padding, border or rounding. */
    private static void pushFramelessStyle() {
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0f);
    }

    /**
     * Whether the OS windows ImGui creates at the end of this frame get a
     * framebuffer whose alpha the desktop compositor honours. The GLFW backend
     * creates a pop-out's OS window then, from the window hints as they stand,
     * and its renderer clears a window that draws no background to transparent.
     * So the hint is on only in a frame where a frameless pop-out's OS window
     * is due, and off otherwise: the host window and every other pop-out keep
     * an opaque framebuffer.
     */
    private static void requestTransparentWindows(boolean isTransparent) {
        GLFW.glfwWindowHint(GLFW.GLFW_TRANSPARENT_FRAMEBUFFER, isTransparent ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
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
