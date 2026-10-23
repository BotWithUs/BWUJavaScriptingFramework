package com.botwithus.bot.cli.gui.scriptui;

import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.cli.gui.window.BorderHitTest;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A frameless pop-out's resize border ({@link BorderResize}) while its script
 * UI is typing, so the host holds an active item for it ({@link HostFocus}).
 * A press on the border resizes the window, reaches no script widget, ends the
 * script's typing as any press outside its field does, and leaves the main
 * window's buttons and drag strip taking the next press. Drawn as the shell
 * draws it: the main window first, the pop-out at the top level after it, and
 * {@link ScriptUiRenderer#endFrame} before Dear ImGui ends the frame. Real
 * imgui-java natives.
 */
class BorderResizeWhileTypingTest {

    private static final String WINDOW = "frameless##resize-typing-test";
    private static final String OWNER = "client/test/script";
    private static final Instant RUN = Instant.parse("2026-10-22T10:00:00Z");
    private static final float WINDOW_X = 600f;
    private static final float WINDOW_Y = 100f;
    private static final float WINDOW_W = 400f;
    private static final float WINDOW_H = 300f;
    /** The band: 6 px deep, corners reaching 20 px along each side. */
    private static final float BAND = 6f;
    private static final BorderHitTest BORDER = new BorderHitTest(BAND, 20f);
    /** How far inside the right edge a border grab lands, well within the band. */
    private static final float INSIDE = 2f;
    private static final int MIN_W = 120;
    private static final int MIN_H = 80;
    private static final float MOVE_X = 30f;
    /** Window bounds are floats moved by whole pixels; this is far below one. */
    private static final float EPSILON = 0.01f;
    private static final int FRAMELESS = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoBackground
            | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoResize;
    /** A point inside the script's text field, drawn at the panel's top-left. */
    private static final float FIELD_X = WINDOW_X + 10f;
    private static final float FIELD_Y = WINDOW_Y + 8f;
    /** A point inside the field past the end of its text, too far from {@link #FIELD_X} for a double click. */
    private static final float FIELD_END_X = WINDOW_X + 100f;
    /** A script button flush with the panel's right edge, so the band lies over it. */
    private static final float EDGE_BUTTON_W = 40f;
    private static final float EDGE_BUTTON_H = 40f;
    private static final float EDGE_BUTTON_Y = (WINDOW_H - EDGE_BUTTON_H) / 2f;
    private static final float RIGHT_EDGE = WINDOW_X + WINDOW_W;
    private static final float ON_BORDER_X = RIGHT_EDGE - INSIDE;
    /** On the edge button, clear of the band: a press here is the script's. */
    private static final float ON_EDGE_BUTTON_X = RIGHT_EDGE - BAND - (EDGE_BUTTON_W - BAND) / 2f;
    private static final float EDGE_Y = WINDOW_Y + WINDOW_H / 2f;
    /** A main-window button, drawn in the host's window as the page's Start buttons are. */
    private static final float HOST_BUTTON_X = 20f;
    private static final float HOST_BUTTON_Y = 500f;
    private static final float INTO_BUTTON = 6f;
    /** A drag strip like the frameless top bar's {@code ##window-drag}, clear of the pop-out. */
    private static final float DRAG_X = 20f;
    private static final float DRAG_Y = 0f;
    private static final float DRAG_W = 400f;
    private static final float DRAG_H = 24f;
    private static final float ON_DRAG_X = DRAG_X + 100f;
    private static final float ON_DRAG_Y = DRAG_Y + 10f;
    /** Far enough to pass Dear ImGui's drag threshold. */
    private static final float DRAG_DISTANCE = 40f;
    private static final int TEXT_CAPACITY = 32;

    private final ImString text = new ImString(TEXT_CAPACITY);
    private final AtomicBoolean isFieldActive = new AtomicBoolean();
    private final AtomicInteger edgeActivations = new AtomicInteger();
    private final AtomicInteger edgeClicks = new AtomicInteger();
    private final AtomicInteger hostClicks = new AtomicInteger();
    private final AtomicBoolean isMainWindowDragged = new AtomicBoolean();
    /** A text field at the panel's top-left and a button flush with its right edge. */
    private final ScriptUI ui = () -> {
        ImGui.inputText("##field", text);
        isFieldActive.set(ImGui.isItemActive());
        ImGui.setCursorPos(ImGui.getWindowWidth() - EDGE_BUTTON_W, EDGE_BUTTON_Y);
        if (ImGui.button("##edge", EDGE_BUTTON_W, EDGE_BUTTON_H)) {
            edgeClicks.incrementAndGet();
        }
        if (ImGui.isItemActivated()) {
            edgeActivations.incrementAndGet();
        }
    };
    private HeadlessHost host;
    private TypedChars typed;
    private ScriptUiRenderer scriptUis;
    private BorderResize resize;
    private BackgroundDrag drag;
    private float windowX;
    private float windowW;

    @BeforeEach
    void setUp() {
        host = HeadlessHost.create();
        typed = new TypedChars();
        scriptUis = new ScriptUiRenderer(AssertRouter.install(), typed, () -> false);
        resize = new BorderResize();
        drag = new BackgroundDrag();
    }

    @AfterEach
    void tearDown() {
        scriptUis.close();
        host.close();
    }

    /** (r1) The one-frame hold must not eat the press that starts a resize. */
    @Test
    void aBorderPress_whileTheScriptTypes_resizesTheWindow() {
        startTyping();

        resizeRightEdge();

        assertAll(
                () -> assertEquals(WINDOW_X, windowX, EPSILON, "window x"),
                () -> assertEquals(WINDOW_W + MOVE_X, windowW, EPSILON, "the border press resizes while typing"));
    }

    /** (r2) A script widget under the band is never clicked or activated by a border press. */
    @Test
    void aBorderPress_overAScriptWidgetAtTheEdge_neverReachesIt() {
        click(ON_EDGE_BUTTON_X, EDGE_Y);
        assertEquals(1, edgeClicks.get(), "precondition: the edge button is under the pointer just off the band");
        startTyping();

        resizeRightEdge();

        assertAll(
                () -> assertEquals(1, edgeActivations.get(), "a border press must not activate the script's widget"),
                () -> assertEquals(1, edgeClicks.get(), "a border press must not click the script's widget"),
                () -> assertEquals(WINDOW_W + MOVE_X, windowW, EPSILON, "and it resized"));
    }

    /** (r3) A border press is a press outside the field, so the typing ends, as it would anywhere else. */
    @Test
    void aBorderPress_endsTheScriptsTyping() {
        startTyping();

        resizeRightEdge();
        typed.typed('x');
        frame();
        frame();

        assertAll(
                () -> assertFalse(scriptUis.hasKeyboard(), "the host has the keyboard back"),
                () -> assertFalse(isFieldActive.get(), "the field must see the focus go"),
                () -> assertEquals("h", text.get(), "typing after the border press is not the field's"));
    }

    /** (r4, as #107's (a)) After a resize begun while typing, a main-window button takes the next click. */
    @Test
    void afterABorderResize_aMainWindowButtonTakesTheNextClick() {
        startTyping();
        resizeRightEdge();

        click(HOST_BUTTON_X + INTO_BUTTON, HOST_BUTTON_Y + INTO_BUTTON);

        assertEquals(1, hostClicks.get(), "the first click after a resize must reach the main window's button");
    }

    /** (r4, as #107's (c)) After a resize begun while typing, the main window's drag strip drags. */
    @Test
    void afterABorderResize_theMainWindowDragStripDrags() {
        startTyping();
        resizeRightEdge();

        mouse(ON_DRAG_X, ON_DRAG_Y, false);
        frame();
        mouse(ON_DRAG_X, ON_DRAG_Y, true);
        frame();
        mouse(ON_DRAG_X + DRAG_DISTANCE, ON_DRAG_Y, true);
        frame();
        frame();
        mouse(ON_DRAG_X + DRAG_DISTANCE, ON_DRAG_Y, false);
        frame();

        assertTrue(isMainWindowDragged.get(), "the drag strip must take the first press after a resize");
    }

    /** (r4) Typing again after a resize holds for a frame only: the main window still takes the next click. */
    @Test
    void typingAgainAfterABorderResize_stillLeavesTheMainWindowClickable() {
        startTyping();
        resizeRightEdge();
        click(FIELD_END_X, FIELD_Y);
        typed.typed('i');
        frame();
        frame();
        assertTrue(scriptUis.hasKeyboard(), "precondition: typing again after the resize");

        click(HOST_BUTTON_X + INTO_BUTTON, HOST_BUTTON_Y + INTO_BUTTON);

        assertAll(
                () -> assertEquals("hi", text.get()),
                () -> assertEquals(1, hostClicks.get(), "a main-window button must take the click"),
                () -> assertFalse(scriptUis.hasKeyboard(), "and that click ends the typing"));
    }

    /** Clicks the field and types one character into it. */
    private void startTyping() {
        click(FIELD_X, FIELD_Y);
        typed.typed('h');
        frame();
        frame();
        assertTrue(text.get().endsWith("h"), "precondition: the field took the typing");
        assertTrue(scriptUis.hasKeyboard(), "precondition: the script's field holds the host's keyboard");
    }

    /** Grabs the right edge on the centre line, drags it {@link #MOVE_X} right, and lets go. */
    private void resizeRightEdge() {
        mouse(ON_BORDER_X, EDGE_Y, false);
        frame();
        frame();
        mouse(ON_BORDER_X, EDGE_Y, true);
        frame();
        assertTrue(resize.isResizing(WINDOW), "the border press starts a resize");
        mouse(ON_BORDER_X + MOVE_X, EDGE_Y, true);
        frame();
        frame();
        mouse(ON_BORDER_X + MOVE_X, EDGE_Y, false);
        frame();
        frame();
        assertFalse(resize.isResizing(WINDOW), "the resize ends with the button");
    }

    /**
     * Hovers ({@code x}, {@code y}) for a frame, then presses and releases the
     * left button there, with one frame more at the end: the sandbox gets the
     * mouse as events, and Dear ImGui trickles a press and its release over two.
     */
    private void click(float x, float y) {
        mouse(x, y, false);
        frame();
        mouse(x, y, true);
        frame();
        mouse(x, y, false);
        frame();
        frame();
    }

    private void frame() {
        host.frame(this::mainWindow, this::popOut, scriptUis::endFrame);
    }

    /** A Start-style button and a drag strip like the frameless top bar's. */
    private void mainWindow() {
        ImGui.setCursorScreenPos(HOST_BUTTON_X, HOST_BUTTON_Y);
        if (ImGui.button("Start")) {
            hostClicks.incrementAndGet();
        }
        ImGui.setCursorScreenPos(DRAG_X, DRAG_Y);
        ImGui.invisibleButton("##window-drag", DRAG_W, DRAG_H);
        if (ImGui.isItemActive() && ImGui.isMouseDragging(ImGuiMouseButton.Left)) {
            isMainWindowDragged.set(true);
        }
    }

    /** The frameless pop-out as {@code ScriptUiWindows.renderFrameless} draws it. */
    private void popOut() {
        ImGui.setNextWindowPos(WINDOW_X, WINDOW_Y, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(WINDOW_W, WINDOW_H, ImGuiCond.FirstUseEver);
        drag.beforeBegin(WINDOW);
        resize.beforeBegin(WINDOW, MIN_W, MIN_H);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.begin(WINDOW, FRAMELESS);
        ImGui.popStyleVar();
        boolean isPointerTaken = resize.takesPointer(WINDOW, BORDER);
        drag.afterDraw(WINDOW, scriptUis.draw(new ScriptUiSlot(OWNER, "script", ui, RUN), isPointerTaken));
        windowX = ImGui.getWindowPosX();
        windowW = ImGui.getWindowWidth();
        ImGui.end();
    }

    private static void mouse(float x, float y, boolean isDown) {
        ImGuiIO io = ImGui.getIO();
        io.addMousePosEvent(x, y);
        io.addMouseButtonEvent(ImGuiMouseButton.Left, isDown);
    }
}
