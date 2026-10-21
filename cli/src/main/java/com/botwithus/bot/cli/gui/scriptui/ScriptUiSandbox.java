package com.botwithus.bot.cli.gui.scriptui;

import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.api.ui.ScriptUiHost;

import imgui.ImFontAtlas;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiBackendFlags;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.internal.ImGuiContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One script UI's own Dear ImGui context, rendered on its own thread. The
 * host's context never sees a script's ImGui call, so a script that leaves a
 * stack unbalanced, or ends something it never began, can only break this
 * context, and a failed assertion parks this sandbox's thread instead of
 * exiting the process (see {@link AssertRouter}).
 *
 * <p>The context shares the host's font atlas, so fonts and texture ids are the
 * host's own. It has no ini file, no docking and no viewports: everything the
 * script draws, including popups and tooltips, lands in one root window the
 * size of the panel.</p>
 *
 * <p>Lifecycle: an assertion abandons the context and thread for good (they are
 * leaked, never touched again). A {@code render()} that throws only costs the
 * context, which is destroyed and made afresh next frame. UI thread only,
 * except {@link #frame}, which runs on the worker.</p>
 */
final class ScriptUiSandbox {

    private static final Logger log = LoggerFactory.getLogger(ScriptUiSandbox.class);

    private static final String ROOT_WINDOW = "##script-ui-root";
    /**
     * Like the host's script-UI child: no title, not movable, scrolls when the
     * UI is taller than the panel. No background: the host's panel shows through.
     */
    private static final int ROOT_FLAGS = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize
            | ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoSavedSettings
            | ImGuiWindowFlags.NoBringToFrontOnFocus | ImGuiWindowFlags.NoBackground;
    /** Root-window style pushed around {@code begin} so it matches a borderless child: no padding, no border. */
    private static final int ROOT_STYLE_VARS = 3;

    private final ImFontAtlas atlas;
    private final SandboxWorker worker;
    private ImGuiContext context;
    /** Whether {@link #context} has had its one-time IO set-up; reset with each new context. */
    private boolean isConfigured;
    private boolean isAbandoned;

    ScriptUiSandbox(long atlasPtr, SandboxWorker worker) {
        this.atlas = new ImFontAtlas(atlasPtr);
        this.worker = worker;
    }

    /** True once an assertion parked the worker: this sandbox will never draw again. */
    boolean isAbandoned() {
        return isAbandoned;
    }

    String threadName() {
        return worker.name();
    }

    /**
     * Draws one frame of {@code ui} on the worker, attached to {@code host} first.
     * The host's context is current again on return. {@code ui} may be a
     * different object every frame (a script may build a new one per
     * {@code getUI()} call); the context, and with it the
     * UI's own state such as an open combo or a scroll position, belongs to the
     * script, not to the object.
     */
    FrameOutcome render(ScriptUI ui, ScriptUiHost host, FrameInput input) {
        ImGuiContext hostContext = ImGui.getCurrentContext();
        if (context == null) {
            context = ImGui.createContext(atlas);
            ImGui.setCurrentContext(hostContext);
        }
        ImGuiContext sandbox = context;
        boolean needsConfigure = !isConfigured;
        isConfigured = true;
        FrameOutcome outcome = worker.run(() -> frame(sandbox, needsConfigure, ui, host, input));
        ImGui.setCurrentContext(hostContext);
        switch (outcome) {
            case FrameOutcome.Asserted ignored -> abandon();
            case FrameOutcome.Threw ignored -> destroyContext();
            case FrameOutcome.Crashed ignored -> destroyContext();
            case FrameOutcome.Drawn ignored -> { }
        }
        return outcome;
    }

    /** Frees the context and lets the worker end. An abandoned sandbox is left exactly as it is. */
    void dispose() {
        if (!isAbandoned) {
            destroyContext();
            worker.stop();
        }
    }

    private void abandon() {
        isAbandoned = true;
        context = null;
    }

    private void destroyContext() {
        if (context != null) {
            ImGui.destroyContext(context);
            context = null;
            isConfigured = false;
        }
    }

    /**
     * On the worker. Any Dear ImGui assertion in here parks this thread for good.
     * Every call into the script ({@code attach}, {@code render},
     * {@code drawsOwnChrome}) happens here, with the sandbox's context current.
     */
    private static FrameOutcome frame(ImGuiContext sandbox, boolean needsConfigure, ScriptUI ui,
                                      ScriptUiHost host, FrameInput input) {
        ImGui.setCurrentContext(sandbox);
        ImGuiIO io = ImGui.getIO();
        if (needsConfigure) {
            configure(io);
        }
        input.applyTo(io);
        input.style().applyTo(ImGui.getStyle());
        ImGui.newFrame();
        beginRoot(input);
        try {
            ui.attach(host);
            ui.render();
        } catch (RuntimeException e) {
            return new FrameOutcome.Threw(e);
        }
        boolean isBackgroundPress = isBackgroundPress();
        boolean drawsOwnChrome = askDrawsOwnChrome(ui);
        ImGui.end();
        ImGui.render();
        return new FrameOutcome.Drawn(DrawCopy.capture(ImGui.getDrawData()), ImGui.getMouseCursor(),
                wantsKeyboard(io), isBackgroundPress, drawsOwnChrome);
    }

    /**
     * A text field is taking typing, or one of the script's items is active.
     * Deliberately not {@code WantCaptureKeyboard}: with keyboard navigation on,
     * that stays true for as long as the root window has focus, which is from
     * the first press on it onwards, and the host would then hold the
     * keyboard, and with it every other item, for good.
     */
    private static boolean wantsKeyboard(ImGuiIO io) {
        return io.getWantTextInput() || ImGui.isAnyItemActive();
    }

    /**
     * After {@code render()}, with the root window current: the left button went
     * down this frame over the root or one of its child windows, on no item. A
     * press on a widget, a scrollbar or a popup (popups are windows of their own)
     * is the script's, so it never counts. This is the press Dear ImGui itself
     * would take to move a window.
     */
    private static boolean isBackgroundPress() {
        return ImGui.isMouseClicked(ImGuiMouseButton.Left) && !ImGui.isAnyItemHovered()
                && ImGui.isWindowHovered(ImGuiHoveredFlags.ChildWindows);
    }

    /** The script's answer, mid-frame so any Dear ImGui call in it lands here; false if it throws. */
    private static boolean askDrawsOwnChrome(ScriptUI ui) {
        try {
            return ui.drawsOwnChrome();
        } catch (RuntimeException e) {
            log.debug("drawsOwnChrome() threw: {}", e.toString());
            return false;
        }
    }

    /**
     * Once per context, before its first frame. The null ini name keeps the
     * sandbox from reading or writing an {@code imgui.ini} in the working
     * directory; it is safe only because nothing in the process ever sets a
     * non-null one ({@code HostIni} says why).
     */
    private static void configure(ImGuiIO io) {
        io.setIniFilename(null);
        io.setConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);
        io.setBackendFlags(ImGuiBackendFlags.RendererHasVtxOffset);
    }

    private static void beginRoot(FrameInput input) {
        ImGui.setNextWindowPos(0f, 0f);
        ImGui.setNextWindowSize(input.width(), input.height());
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0f);
        ImGui.begin(ROOT_WINDOW, ROOT_FLAGS);
        ImGui.popStyleVar(ROOT_STYLE_VARS);
    }
}
