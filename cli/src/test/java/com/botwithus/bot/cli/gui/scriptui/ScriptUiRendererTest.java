package com.botwithus.bot.cli.gui.scriptui;

import com.botwithus.bot.api.ui.ScriptUI;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImString;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScriptUiRenderer} in this JVM, against the real imgui-java natives. With
 * the guard working, a broken UI here only parks a thread; if the guard stops
 * working, imgui-java exits the test JVM, which Gradle reports as a failure.
 */
class ScriptUiRendererTest {

    private static final String OWNER = "client/test/script";
    private static final Instant FIRST_RUN = Instant.parse("2026-10-12T10:00:00Z");
    private static final Instant SECOND_RUN = Instant.parse("2026-10-12T11:00:00Z");
    private static final float PANEL_X = 20f;
    private static final float PANEL_Y = 30f;
    private static final float PANEL_W = 400f;
    private static final float PANEL_H = 300f;
    /** A point inside the first widget the test UIs draw, in host screen coordinates. */
    private static final float FIRST_WIDGET_X = PANEL_X + 10f;
    private static final float FIRST_WIDGET_Y = PANEL_Y + 8f;
    private static final int TEXT_CAPACITY = 32;
    /** A host button below the panel, drawn before it, as the page's Start buttons are. */
    private static final float HOST_BUTTON_X = PANEL_X;
    private static final float HOST_BUTTON_Y = PANEL_Y + PANEL_H + 70f;
    /** A point inside the host button. */
    private static final float ON_HOST_BUTTON_X = HOST_BUTTON_X + 10f;
    private static final float ON_HOST_BUTTON_Y = HOST_BUTTON_Y + 8f;
    /** A drag strip like the frameless top bar's {@code ##window-drag}, clear of the panel. */
    private static final float DRAG_X = 600f;
    private static final float DRAG_Y = 0f;
    private static final float DRAG_W = 400f;
    private static final float DRAG_H = 24f;
    private static final float ON_DRAG_X = DRAG_X + 100f;
    private static final float ON_DRAG_Y = DRAG_Y + 10f;
    /** Far enough to pass Dear ImGui's drag threshold. */
    private static final float DRAG_DISTANCE = 40f;
    /** A popped-out window, clear of the host button, with the script's panel inside it. */
    private static final float POP_OUT_X = 600f;
    private static final float POP_OUT_Y = 100f;
    private static final float POP_OUT_W = 450f;
    private static final float POP_OUT_H = 350f;
    private static final float POP_OUT_PANEL_X = POP_OUT_X + 20f;
    private static final float POP_OUT_PANEL_Y = POP_OUT_Y + 50f;
    private static final float POP_OUT_PANEL_H = 250f;
    private static final float POP_OUT_WIDGET_X = POP_OUT_PANEL_X + 10f;
    private static final float POP_OUT_WIDGET_Y = POP_OUT_PANEL_Y + 8f;
    /** Host frames that pass with nothing happening, so nothing depends on the frame a press lands in. */
    private static final int IDLE_FRAMES = 3;
    private static final float SLIDER_MAX = 100f;
    /** Where a slider drag starts and passes, as fractions of the panel's width. */
    private static final float SLIDER_START = 0.1f;
    private static final float SLIDER_MID = 0.5f;

    private HeadlessHost host;
    private ScriptUiRenderer scriptUis;
    private TypedChars typed;
    private final AtomicInteger reloadOffered = new AtomicInteger();
    private boolean isReloadClicked;

    @BeforeEach
    void setUp() {
        host = HeadlessHost.create();
        typed = new TypedChars();
        scriptUis = new ScriptUiRenderer(AssertRouter.install(), typed, () -> {
            reloadOffered.incrementAndGet();
            return isReloadClicked;
        });
    }

    @AfterEach
    void tearDown() {
        scriptUis.close();
        host.close();
    }

    @Test
    void anAssertion_stopsTheUi_andOffersReload_untilThreeFailures() {
        ScriptUI broken = () -> ImGui.popStyleVar();
        for (int failure = 1; failure <= ScriptUiRenderer.MAX_FAILURES; failure++) {
            isReloadClicked = false;
            frame(broken, FIRST_RUN);
            frame(broken, FIRST_RUN);
            SlotState state = state();
            assertTrue(state.isStopped(), "failure " + failure);
            assertEquals(failure, state.failures());
            assertEquals(failure < ScriptUiRenderer.MAX_FAILURES, state.canReload());
            isReloadClicked = true;
            frame(broken, FIRST_RUN);
            assertEquals(!state.canReload(), state.isStopped(), "after clicking Reload, failure " + failure);
        }
        int offeredBefore = reloadOffered.get();
        frame(broken, FIRST_RUN);
        frame(broken, FIRST_RUN);

        assertEquals(offeredBefore, reloadOffered.get(), "Reload must not be drawn after the last failure");
        assertTrue(state().isStopped());
        assertEquals(ScriptUiRenderer.MAX_FAILURES, state().failures());
    }

    @Test
    void aRestart_clearsTheFailures_andDrawsTheUiAgain() {
        AtomicInteger renders = new AtomicInteger();
        ScriptUI brokenOnce = () -> {
            renders.incrementAndGet();
            if (renders.get() == 1) {
                ImGui.pushStyleVar(ImGuiStyleVar.Alpha, 1f);
            }
        };
        frame(brokenOnce, FIRST_RUN);
        frame(brokenOnce, FIRST_RUN);
        assertTrue(state().isStopped());
        assertEquals(1, renders.get(), "a stopped UI is not rendered");

        frame(brokenOnce, SECOND_RUN);

        assertFalse(state().isStopped());
        assertEquals(0, state().failures());
        assertEquals(2, renders.get());
    }

    @Test
    void aThrowingUi_showsItsMessage_andIsDrawnAgainNextFrame_asBeforeSandboxing() {
        AtomicInteger renders = new AtomicInteger();
        ScriptUI throwsMidWay = () -> {
            renders.incrementAndGet();
            ImGui.pushStyleVar(ImGuiStyleVar.Alpha, 1f);
            ImGui.beginGroup();
            throw new IllegalStateException("boom");
        };
        frame(throwsMidWay, FIRST_RUN);
        frame(throwsMidWay, FIRST_RUN);
        frame(throwsMidWay, FIRST_RUN);

        assertEquals(3, renders.get());
        assertFalse(state().isStopped(), "an exception is not a broken ImGui rule");
        assertEquals(0, state().failures());
    }

    @Test
    void aClick_overThePanel_reachesTheScriptsButton() {
        AtomicInteger clicks = new AtomicInteger();
        ScriptUI ui = () -> {
            if (ImGui.button("Press me")) {
                clicks.incrementAndGet();
            }
        };
        frame(ui, FIRST_RUN);
        mouse(FIRST_WIDGET_X, FIRST_WIDGET_Y, false);
        frame(ui, FIRST_RUN);
        mouse(FIRST_WIDGET_X, FIRST_WIDGET_Y, true);
        frame(ui, FIRST_RUN);
        mouse(FIRST_WIDGET_X, FIRST_WIDGET_Y, false);
        frame(ui, FIRST_RUN);

        assertEquals(1, clicks.get());
    }

    @Test
    void aClick_outsideThePanel_doesNotReachTheScript() {
        AtomicInteger clicks = new AtomicInteger();
        ScriptUI ui = () -> {
            if (ImGui.button("Press me")) {
                clicks.incrementAndGet();
            }
        };
        float outsideX = PANEL_X + PANEL_W + 50f;
        frame(ui, FIRST_RUN);
        mouse(outsideX, FIRST_WIDGET_Y, false);
        frame(ui, FIRST_RUN);
        mouse(outsideX, FIRST_WIDGET_Y, true);
        frame(ui, FIRST_RUN);
        mouse(outsideX, FIRST_WIDGET_Y, false);
        frame(ui, FIRST_RUN);

        assertEquals(0, clicks.get());
    }

    @Test
    void typing_intoTheScriptsTextField_reachesIt_andHoldsTheHostsKeyboard() {
        ImString text = new ImString(TEXT_CAPACITY);
        ScriptUI ui = () -> ImGui.inputText("##field", text);
        frame(ui, FIRST_RUN);
        mouse(FIRST_WIDGET_X, FIRST_WIDGET_Y, false);
        frame(ui, FIRST_RUN);
        mouse(FIRST_WIDGET_X, FIRST_WIDGET_Y, true);
        frame(ui, FIRST_RUN);
        mouse(FIRST_WIDGET_X, FIRST_WIDGET_Y, false);
        frame(ui, FIRST_RUN);
        typed.typed('h');
        typed.typed('i');
        frame(ui, FIRST_RUN);
        frame(ui, FIRST_RUN);

        assertEquals("hi", text.get());
        assertTrue(scriptUis.hasKeyboard(), "the host must know a script field has the keyboard");
    }

    @Test
    void aPressOnAScriptButton_leavesTheHostsButtonsClickable() {
        AtomicInteger scriptClicks = new AtomicInteger();
        AtomicInteger hostClicks = new AtomicInteger();
        ScriptUI ui = countingButton(scriptClicks);
        Runnable frame = () -> hostFrame(ui, hostButton(hostClicks));

        click(FIRST_WIDGET_X, FIRST_WIDGET_Y, frame);
        idle(frame);
        click(ON_HOST_BUTTON_X, ON_HOST_BUTTON_Y, frame);

        assertEquals(1, scriptClicks.get(), "the script's own button still works");
        assertEquals(1, hostClicks.get(), "a host button drawn before the panel must take the next click");
        assertFalse(scriptUis.hasKeyboard(), "a plain button never has the keyboard");
    }

    @Test
    void aPressOnAScriptButton_leavesTheWindowDragRegionDraggable() {
        AtomicBoolean isDragged = new AtomicBoolean();
        ScriptUI ui = countingButton(new AtomicInteger());
        Runnable frame = () -> hostFrame(ui, dragRegion(isDragged));

        click(FIRST_WIDGET_X, FIRST_WIDGET_Y, frame);
        idle(frame);
        mouse(ON_DRAG_X, ON_DRAG_Y, false);
        frame.run();
        mouse(ON_DRAG_X, ON_DRAG_Y, true);
        frame.run();
        mouse(ON_DRAG_X + DRAG_DISTANCE, ON_DRAG_Y, true);
        frame.run();
        frame.run();
        mouse(ON_DRAG_X + DRAG_DISTANCE, ON_DRAG_Y, false);
        frame.run();

        assertTrue(isDragged.get(), "the drag region must become active and drag after a press in the script's UI");
    }

    @Test
    void aPressInAPopOut_leavesTheMainWindowClickable() {
        AtomicInteger scriptClicks = new AtomicInteger();
        AtomicInteger hostClicks = new AtomicInteger();
        ScriptUI ui = countingButton(scriptClicks);
        Runnable frame = () -> popOutFrame(ui, hostButton(hostClicks));

        click(POP_OUT_WIDGET_X, POP_OUT_WIDGET_Y, frame);
        idle(frame);
        click(ON_HOST_BUTTON_X, ON_HOST_BUTTON_Y, frame);

        assertEquals(1, scriptClicks.get(), "the pop-out's button still works");
        assertEquals(1, hostClicks.get(), "a main-window button must take the click after a press in a pop-out");
    }

    @Test
    void whileTyping_theHostSeesAnActiveItem_andAClickOutsideTakesTheKeyboardBack() {
        ImString text = new ImString(TEXT_CAPACITY);
        AtomicInteger hostClicks = new AtomicInteger();
        AtomicBoolean isHostItemActive = new AtomicBoolean();
        ScriptUI ui = () -> ImGui.inputText("##field", text);
        Runnable frame = () -> hostFrame(ui, hostButton(hostClicks),
                () -> isHostItemActive.set(ImGui.isAnyItemActive()));

        click(FIRST_WIDGET_X, FIRST_WIDGET_Y, frame);
        typed.typed('h');
        typed.typed('i');
        frame.run();
        frame.run();
        assertEquals("hi", text.get());
        assertTrue(scriptUis.hasKeyboard(), "a script field has the keyboard");
        assertTrue(isHostItemActive.get(), "host items after the panel must see an active item while typing");

        click(ON_HOST_BUTTON_X, ON_HOST_BUTTON_Y, frame);
        typed.typed('x');
        frame.run();
        idle(frame);

        assertEquals(1, hostClicks.get(), "a host button must take a click while a script field is active");
        assertFalse(scriptUis.hasKeyboard(), "a click outside the panel ends the script's typing");
        assertFalse(isHostItemActive.get(), "the host must not stay held once typing ends");
        assertEquals("hi", text.get(), "nothing typed after the click reaches the script's field");
    }

    @Test
    void whenTheScriptsFieldLetsGo_theHostGetsTheKeyboardBack() {
        ImString text = new ImString(TEXT_CAPACITY);
        AtomicBoolean isHostItemActive = new AtomicBoolean();
        ScriptUI ui = () -> ImGui.inputText("##field", text);
        Runnable frame = () -> hostFrame(ui, () -> { }, () -> isHostItemActive.set(ImGui.isAnyItemActive()));

        click(FIRST_WIDGET_X, FIRST_WIDGET_Y, frame);
        frame.run();
        assertTrue(scriptUis.hasKeyboard(), "a script field has the keyboard");

        ImGui.getIO().addKeyEvent(ImGuiKey.Enter, true);
        frame.run();
        ImGui.getIO().addKeyEvent(ImGuiKey.Enter, false);
        frame.run();
        idle(frame);

        assertFalse(scriptUis.hasKeyboard(), "Enter ends a single-line field, and with it the script's keyboard");
        assertFalse(isHostItemActive.get(), "the host must not stay held once typing ends");
        assertFalse(state().isStopped());
    }

    @Test
    void whileTyping_escGoesToTheScriptsField_notToAHostShortcutDrawnBeforeThePanel() {
        ImString text = new ImString(TEXT_CAPACITY);
        AtomicInteger hostEscapes = new AtomicInteger();
        ScriptUI ui = () -> ImGui.inputText("##field", text);
        Runnable frame = () -> hostFrame(ui, escapeShortcut(hostEscapes));

        click(FIRST_WIDGET_X, FIRST_WIDGET_Y, frame);
        typed.typed('h');
        frame.run();
        assertEquals("h", text.get(), "the script's field is taking typing");

        ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
        frame.run();
        ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
        frame.run();
        idle(frame);

        assertEquals(0, hostEscapes.get(), "the host's Esc must leave a key meant for the script's field alone");
        assertFalse(scriptUis.hasKeyboard(), "Esc reached the field, which let go of the keyboard");
        assertEquals("", text.get(), "Esc reached the field, which reverted the edit");
    }

    @Test
    void aPressOutsideThePanel_neverClicksAScriptWidget_evenAtTheWidgetsOwnPanelCoordinates() {
        AtomicInteger scriptClicks = new AtomicInteger();
        ScriptUI ui = countingButton(scriptClicks);
        Runnable frame = () -> hostFrame(ui, () -> { });

        click(FIRST_WIDGET_X - PANEL_X, FIRST_WIDGET_Y - PANEL_Y, frame);
        click(ON_HOST_BUTTON_X, ON_HOST_BUTTON_Y, frame);
        click(PANEL_X + PANEL_W + PANEL_X, FIRST_WIDGET_Y, frame);

        assertEquals(0, scriptClicks.get(), "a press the host had must never reach a script's widget");
        assertFalse(state().isStopped(), "a press elsewhere must not trip a Dear ImGui check in the sandbox");
    }

    @Test
    void aPressOutsideThePanel_closesAPopupTheScriptOpened() {
        AtomicBoolean isPopupOpen = new AtomicBoolean();
        ScriptUI ui = () -> {
            if (ImGui.button("Open")) {
                ImGui.openPopup("menu");
            }
            isPopupOpen.set(ImGui.beginPopup("menu"));
            if (isPopupOpen.get()) {
                ImGui.text("item");
                ImGui.endPopup();
            }
        };
        Runnable frame = () -> hostFrame(ui, () -> { });

        click(FIRST_WIDGET_X, FIRST_WIDGET_Y, frame);
        idle(frame);
        assertTrue(isPopupOpen.get(), "the script's button opens its popup");

        click(ON_HOST_BUTTON_X, ON_HOST_BUTTON_Y, frame);
        idle(frame);

        assertFalse(isPopupOpen.get(), "a press elsewhere in the host closes the script's popup, as unsandboxed");
    }

    @Test
    void whileTypingInAPopOut_aMainWindowButtonTakesTheClick_andTheKeyboardComesBack() {
        ImString text = new ImString(TEXT_CAPACITY);
        AtomicInteger hostClicks = new AtomicInteger();
        ScriptUI ui = () -> ImGui.inputText("##field", text);
        Runnable frame = () -> popOutFrame(ui, hostButton(hostClicks));

        click(POP_OUT_WIDGET_X, POP_OUT_WIDGET_Y, frame);
        typed.typed('h');
        frame.run();
        assertEquals("h", text.get());
        assertTrue(scriptUis.hasKeyboard(), "a pop-out's field has the keyboard");

        click(ON_HOST_BUTTON_X, ON_HOST_BUTTON_Y, frame);
        idle(frame);

        assertEquals(1, hostClicks.get(), "a main-window button must take a click while a pop-out's field is active");
        assertFalse(scriptUis.hasKeyboard(), "a click outside the pop-out ends its typing");
    }

    /** Gated the way {@code InspectorDock.handleEscape} is, and drawn before the panel as that is. */
    private Runnable escapeShortcut(AtomicInteger escapes) {
        return () -> {
            if (ImGui.isAnyItemActive() || scriptUis.hasKeyboard()) {
                return;
            }
            if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
                escapes.incrementAndGet();
            }
        };
    }

    @Test
    void aDragThatStartsOnAScriptSlider_carriesOnPastThePanelsEdge() {
        float[] value = {0f};
        ScriptUI ui = () -> {
            ImGui.setNextItemWidth(-1f);
            ImGui.sliderFloat("##slider", value, 0f, SLIDER_MAX);
        };
        Runnable frame = () -> hostFrame(ui, () -> { });
        float startX = PANEL_X + PANEL_W * SLIDER_START;
        float midX = PANEL_X + PANEL_W * SLIDER_MID;

        mouse(startX, FIRST_WIDGET_Y, false);
        idle(frame);
        mouse(startX, FIRST_WIDGET_Y, true);
        frame.run();
        mouse(midX, FIRST_WIDGET_Y, true);
        frame.run();
        assertTrue(value[0] < SLIDER_MAX, "mid-drag, inside the panel");
        mouse(PANEL_X + PANEL_W + DRAG_DISTANCE, FIRST_WIDGET_Y, true);
        frame.run();
        frame.run();
        mouse(PANEL_X + PANEL_W + DRAG_DISTANCE, FIRST_WIDGET_Y, false);
        frame.run();

        assertEquals(SLIDER_MAX, value[0], "a drag the panel started stays the script's outside the panel");
    }

    private static ScriptUI countingButton(AtomicInteger clicks) {
        return () -> {
            if (ImGui.button("Press me")) {
                clicks.incrementAndGet();
            }
        };
    }

    private static Runnable hostButton(AtomicInteger clicks) {
        return () -> {
            ImGui.setCursorScreenPos(HOST_BUTTON_X, HOST_BUTTON_Y);
            if (ImGui.button("Start")) {
                clicks.incrementAndGet();
            }
        };
    }

    /** Mirrors {@code FramelessChrome.renderDragRegion}: an invisible button that drags while active. */
    private static Runnable dragRegion(AtomicBoolean isDragged) {
        return () -> {
            ImGui.setCursorScreenPos(DRAG_X, DRAG_Y);
            ImGui.invisibleButton("##window-drag", DRAG_W, DRAG_H);
            if (ImGui.isItemActive() && ImGui.isMouseDragging(ImGuiMouseButton.Left)) {
                isDragged.set(true);
            }
        };
    }

    /** A host frame with {@code before} drawn ahead of the docked panel, and endFrame where the shell calls it. */
    private void hostFrame(ScriptUI ui, Runnable before) {
        hostFrame(ui, before, () -> { });
    }

    private void hostFrame(ScriptUI ui, Runnable before, Runnable after) {
        host.frame(() -> {
            before.run();
            HeadlessHost.panel("##script-ui", PANEL_X, PANEL_Y, PANEL_W, PANEL_H, () -> scriptUis.draw(slot(ui)));
            after.run();
        }, () -> { }, scriptUis::endFrame);
    }

    /** {@code mainWindow} in the host's window, then the script's UI in a pop-out drawn after it, as the shell does. */
    private void popOutFrame(ScriptUI ui, Runnable mainWindow) {
        host.frame(mainWindow, () -> HeadlessHost.window("Script UI##pop-out", POP_OUT_X, POP_OUT_Y, POP_OUT_W,
                POP_OUT_H, () -> HeadlessHost.panel("##script-ui", POP_OUT_PANEL_X, POP_OUT_PANEL_Y, PANEL_W,
                        POP_OUT_PANEL_H, () -> scriptUis.draw(slot(ui)))),
                scriptUis::endFrame);
    }

    private static ScriptUiSlot slot(ScriptUI ui) {
        return new ScriptUiSlot(OWNER, "script", ui, FIRST_RUN);
    }

    /**
     * Hover, press, release at ({@code x}, {@code y}), one frame each, after a
     * frame with the mouse already there: no user presses on the frame the
     * panel first appears, and Dear ImGui hovers windows from the frame before.
     */
    private static void click(float x, float y, Runnable frame) {
        mouse(x, y, false);
        frame.run();
        mouse(x, y, false);
        frame.run();
        mouse(x, y, true);
        frame.run();
        mouse(x, y, false);
        frame.run();
    }

    private static void idle(Runnable frame) {
        for (int i = 0; i < IDLE_FRAMES; i++) {
            frame.run();
        }
    }


    private void frame(ScriptUI ui, Instant startedAt) {
        host.frame(() -> HeadlessHost.panel("##script-ui", PANEL_X, PANEL_Y, PANEL_W, PANEL_H,
                () -> scriptUis.draw(new ScriptUiSlot(OWNER, "script", ui, startedAt))));
        scriptUis.endFrame();
    }

    private static void mouse(float x, float y, boolean isDown) {
        ImGuiIO io = ImGui.getIO();
        io.addMousePosEvent(x, y);
        io.addMouseButtonEvent(ImGuiMouseButton.Left, isDown);
    }

    private SlotState state() {
        return scriptUis.state(OWNER).orElseThrow();
    }
}
