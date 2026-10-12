package com.botwithus.bot.cli.gui.scriptui;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiWindowFlags;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Draws scripts' own UIs ({@code ScriptUI.render()}) so that a broken one cannot
 * take the host down. Each UI renders in a {@link ScriptUiSandbox}, and what it
 * drew is composited into the host's panel. A UI that breaks a Dear ImGui rule
 * (one {@code popStyleVar} too many, a {@code begin} without its {@code end})
 * is stopped: the panel says what broke, the host and every other script and
 * client carry on, and a Reload button gives it a new sandbox, up to
 * {@link #MAX_FAILURES} times per run of the script.
 *
 * <p>A UI that throws behaves as it always has: the panel shows the exception's
 * message for that frame and the UI is drawn again on the next one. A UI that
 * never returns from {@code render()} still blocks the UI thread; see
 * {@link SandboxWorker} for why there is no timeout.</p>
 *
 * <p>UI thread only. Call {@link #endFrame} once per host frame.</p>
 */
public final class ScriptUiHost {

    /** Assertions per run of a script before Reload is withdrawn until the script is restarted. */
    public static final int MAX_FAILURES = 3;

    private static final Logger log = LoggerFactory.getLogger(ScriptUiHost.class);

    /** How long an undrawn UI keeps its sandbox, in host frames: about five seconds at 60 fps. */
    private static final int IDLE_FRAMES = 300;
    private static final String AREA_ID = "##script-ui-sandbox";
    /** The sandbox scrolls and navigates itself; the host's panel must not. */
    private static final int AREA_FLAGS = ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse
            | ImGuiWindowFlags.NoNavInputs;
    private static final String THREW = "This script's UI failed to draw: ";
    private static final String BROKE = "This script's UI broke a Dear ImGui rule, so the host stopped drawing it."
            + " The host and your other scripts are unaffected.";
    private static final String RELOAD = "Reload UI";

    private final AssertRouter router;
    private final TypedChars typed;
    private final BooleanSupplier reloadButton;
    private final StyleSnapshot style = new StyleSnapshot();
    private final Map<String, SlotState> slots = new HashMap<>();

    /**
     * @param router       the process's assertion handler
     * @param typed        characters typed since the last frame
     * @param reloadButton draws the stopped panel's Reload button; true when clicked
     */
    public ScriptUiHost(AssertRouter router, TypedChars typed, BooleanSupplier reloadButton) {
        this.router = router;
        this.typed = typed;
        this.reloadButton = reloadButton;
    }

    /** The host's own: the Reload button in the shared widget kit's style. */
    public static ScriptUiHost withControls(AssertRouter router, TypedChars typed, Controls ui) {
        return new ScriptUiHost(router, typed,
                () -> ui.button("##script-ui-reload", Icons.ROTATE, RELOAD, Tone.SOFT, true));
    }

    /** Draws {@code slot}'s UI in the space left in the host's current window. */
    public void draw(ScriptUiSlot slot) {
        SlotState state = slots.computeIfAbsent(slot.owner(), owner -> new SlotState(MAX_FAILURES));
        state.observe(slot, ImGui.getFrameCount());
        float width = ImGui.getContentRegionAvailX();
        float height = ImGui.getContentRegionAvailY();
        if (width <= 0f || height <= 0f) {
            return;
        }
        ImGui.beginChild(AREA_ID, width, height, false, AREA_FLAGS);
        if (state.isStopped()) {
            renderStopped(state);
        } else {
            renderSandboxed(slot, state, width, height);
        }
        ImGui.endChild();
    }

    /**
     * True while a script UI's text field or keyboard navigation has the keyboard,
     * as of the last frame: host-wide shortcuts should leave the keystroke alone.
     */
    public boolean hasKeyboard() {
        for (SlotState state : slots.values()) {
            if (state.wantsKeyboard()) {
                return true;
            }
        }
        return false;
    }

    /** Once per host frame, after everything is drawn: drops stray typing and idle sandboxes. */
    public void endFrame() {
        typed.clear();
        int frame = ImGui.getFrameCount();
        for (SlotState state : slots.values()) {
            if (state.isIdleSince(frame, IDLE_FRAMES)) {
                state.disposeSandbox();
            }
        }
    }

    /** What the host knows about {@code owner}'s UI, if it has drawn it. */
    Optional<SlotState> state(String owner) {
        return Optional.ofNullable(slots.get(owner));
    }

    /** Frees every live sandbox. Call before the host's own context is destroyed. */
    public void close() {
        slots.values().forEach(SlotState::disposeSandbox);
    }

    private void renderSandboxed(ScriptUiSlot slot, SlotState state, float width, float height) {
        PanelInput panel = PanelInput.submit(width, height);
        ScriptUiSandbox sandbox = state.sandbox(() -> newSandbox(slot, state));
        FrameOutcome outcome = sandbox.render(slot.ui(), panel.frame(width, height, typed, style));
        switch (outcome) {
            case FrameOutcome.Drawn drawn -> composite(drawn, panel, state);
            case FrameOutcome.Threw threw -> renderThrew(slot, threw.error(), panel);
            case FrameOutcome.Asserted asserted -> stop(slot, state, sandbox, asserted.assertion(), panel);
            case FrameOutcome.Crashed crashed -> throw crashed.error();
        }
    }

    private ScriptUiSandbox newSandbox(ScriptUiSlot slot, SlotState state) {
        String name = "script-ui " + slot.label() + " #" + state.generation();
        return new ScriptUiSandbox(ImGui.getIO().getFonts().ptr, new SandboxWorker(name, router));
    }

    private static void composite(FrameOutcome.Drawn drawn, PanelInput panel, SlotState state) {
        drawn.draw().compositeInto(ImGui.getWindowDrawList(), panel.originX(), panel.originY());
        if (panel.isOver()) {
            ImGui.setMouseCursor(drawn.mouseCursor());
        }
        boolean isTyping = drawn.wantsKeyboard() && panel.isFocused();
        state.wantsKeyboard(isTyping);
        if (isTyping) {
            HostFocus.hold(panel.itemId());
        }
    }

    /** What the panel showed before sandboxing when {@code render()} threw; the UI is tried again next frame. */
    private static void renderThrew(ScriptUiSlot slot, RuntimeException error, PanelInput panel) {
        log.debug("Script UI for {} threw: {}", slot.label(), error.toString());
        ImGui.setCursorScreenPos(panel.originX(), panel.originY());
        ImGui.pushStyleColor(ImGuiCol.Text, ImGuiTheme.COL_DANGER);
        ImGui.textWrapped(THREW + error.getMessage());
        ImGui.popStyleColor();
    }

    private void stop(ScriptUiSlot slot, SlotState state, ScriptUiSandbox sandbox, ImGuiAssertion assertion,
                      PanelInput panel) {
        state.failed(assertion);
        log.warn("Script UI for {} broke a Dear ImGui rule and was stopped (failure {} of {} this run): {}."
                        + " Its render thread '{}' stays parked and its ImGui context is abandoned.",
                slot.label(), state.failures(), MAX_FAILURES, assertion.describe(), sandbox.threadName());
        ImGui.setCursorScreenPos(panel.originX(), panel.originY());
        renderStopped(state);
    }

    private void renderStopped(SlotState state) {
        ImGui.pushStyleColor(ImGuiCol.Text, ImGuiTheme.COL_DANGER);
        ImGui.textWrapped(BROKE);
        ImGui.popStyleColor();
        ImGui.pushStyleColor(ImGuiCol.Text, ImGuiTheme.COL_FG2);
        state.lastFailure().ifPresent(failed -> ImGui.textWrapped("Dear ImGui check: " + failed.describe()));
        ImGui.textWrapped(reloadNote(state));
        ImGui.popStyleColor();
        if (state.canReload() && reloadButton.getAsBoolean()) {
            state.reload();
        }
    }

    private static String reloadNote(SlotState state) {
        if (state.canReload()) {
            return "Failures this run: " + state.failures() + " of " + MAX_FAILURES + ".";
        }
        return "It has failed " + state.failures() + " times this run. Reload is off until the script is restarted.";
    }
}
