package com.botwithus.bot.cli.gui.scriptui;

import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.cli.gui.window.BorderHitTest;
import com.botwithus.bot.cli.gui.window.ResizeEdge;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A frameless window around a sandboxed script UI, the way a pop-out that
 * draws its own chrome is drawn, resized from its edges by the host: a press in
 * the border band resizes the window and never reaches the script or starts a
 * background drag, and a press anywhere else is the script's as before. Real
 * imgui-java natives, no viewports, and no padding, so the script's panel
 * covers the whole window as it does in a pop-out.
 */
class BorderResizeTest {

    private static final String WINDOW = "frameless##resize-test";
    private static final String OWNER = "client/test/script";
    private static final Instant RUN = Instant.parse("2026-10-20T10:00:00Z");
    private static final float WINDOW_X = 100f;
    private static final float WINDOW_Y = 100f;
    private static final float WINDOW_W = 400f;
    private static final float WINDOW_H = 300f;
    /** The band: 6 px deep, corners reaching 20 px along each side. */
    private static final BorderHitTest BORDER = new BorderHitTest(6f, 20f);
    /** How far inside an edge a grab lands, well within the band. */
    private static final float INSIDE = 2f;
    private static final int MIN_W = 120;
    private static final int MIN_H = 80;
    private static final float MOVE_X = 30f;
    private static final float MOVE_Y = 20f;
    /** Far enough to shrink the window past its minimum on either axis. */
    private static final float COLLAPSE = 1000f;
    /** A point in the window's lower half, where the default test UI draws nothing. */
    private static final float EMPTY_X = WINDOW_X + 250f;
    private static final float EMPTY_Y = WINDOW_Y + 200f;
    private static final float CENTRE_X = WINDOW_X + WINDOW_W / 2f;
    private static final float CENTRE_Y = WINDOW_Y + WINDOW_H / 2f;
    /** Window bounds are floats moved by whole pixels; this is far below one. */
    private static final float EPSILON = 0.01f;
    private static final int FRAMELESS = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoBackground
            | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoResize;

    private final AtomicInteger presses = new AtomicInteger();
    /** A button at the panel's top-left; the rest of the panel is background. */
    private ScriptUI ui = () -> {
        if (ImGui.button("Press me")) {
            presses.incrementAndGet();
        }
    };
    private HeadlessHost host;
    private ScriptUiRenderer scriptUis;
    private BorderResize resize;
    private BackgroundDrag drag;
    private float windowX;
    private float windowY;
    private float windowW;
    private float windowH;
    private boolean wasBackgroundPress;
    private int cursor;

    @BeforeEach
    void setUp() {
        host = HeadlessHost.create();
        scriptUis = new ScriptUiRenderer(AssertRouter.install(), new TypedChars(), () -> false);
        resize = new BorderResize();
        drag = new BackgroundDrag();
    }

    @AfterEach
    void tearDown() {
        scriptUis.close();
        host.close();
    }

    @ParameterizedTest
    @EnumSource(ResizeEdge.class)
    void aDragInTheBorder_resizesFromThatEdge_andTheScriptNeverSeesIt(ResizeEdge edge) {
        ui = panelWideButton();
        float grabX = edge.isLeft() ? WINDOW_X + INSIDE : edge.isRight() ? WINDOW_X + WINDOW_W - INSIDE : CENTRE_X;
        float grabY = edge.isTop() ? WINDOW_Y + INSIDE : edge.isBottom() ? WINDOW_Y + WINDOW_H - INSIDE : CENTRE_Y;

        hoverAt(grabX, grabY);
        assertEquals(edge.mouseCursor(), cursor, "hovering the band shows the edge's resize cursor");
        press();
        assertTrue(resize.isResizing(WINDOW));

        mouse(grabX + MOVE_X, grabY + MOVE_Y, true);
        frame();
        frame();
        release();

        float dx = edge.isLeft() || edge.isRight() ? MOVE_X : 0f;
        float dy = edge.isTop() || edge.isBottom() ? MOVE_Y : 0f;
        assertAll(
                () -> assertFalse(resize.isResizing(WINDOW)),
                () -> assertBounds(edge.isLeft() ? WINDOW_X + dx : WINDOW_X, edge.isTop() ? WINDOW_Y + dy : WINDOW_Y,
                        edge.isLeft() ? WINDOW_W - dx : WINDOW_W + dx, edge.isTop() ? WINDOW_H - dy : WINDOW_H + dy),
                () -> assertEquals(0, presses.get(), "a press in the border must not reach the script"));
    }

    @Test
    void aPressInTheBorder_overTheBackground_doesNotMoveTheWindow() {
        float bottom = WINDOW_Y + WINDOW_H - INSIDE;

        hoverAt(EMPTY_X, bottom);
        press();
        assertFalse(wasBackgroundPress, "the border's press is not the UI's background press");
        assertFalse(drag.isDragging(WINDOW));

        mouse(EMPTY_X, bottom + MOVE_Y, true);
        frame();
        frame();
        release();

        assertBounds(WINDOW_X, WINDOW_Y, WINDOW_W, WINDOW_H + MOVE_Y);
    }

    @Test
    void aPressInside_stillDragsTheWindow_andShowsNoResizeCursor() {
        hoverAt(EMPTY_X, EMPTY_Y);
        assertNotEquals(ImGuiMouseCursor.ResizeEW, cursor);
        assertNotEquals(ImGuiMouseCursor.ResizeNS, cursor);
        press();
        assertTrue(drag.isDragging(WINDOW));
        assertFalse(resize.isResizing(WINDOW));

        mouse(EMPTY_X + MOVE_X, EMPTY_Y + MOVE_Y, true);
        frame();
        frame();
        release();

        assertBounds(WINDOW_X + MOVE_X, WINDOW_Y + MOVE_Y, WINDOW_W, WINDOW_H);
    }

    @Test
    void aDragThatBeganOnTheScript_staysTheScripts_whenItCrossesTheBorder() {
        ui = panelWideButton();
        float right = WINDOW_X + WINDOW_W - INSIDE;

        hoverAt(CENTRE_X, CENTRE_Y);
        press();
        mouse(right, CENTRE_Y, true);
        frame();
        frame();
        assertFalse(resize.isResizing(WINDOW));
        mouse(right + MOVE_X, CENTRE_Y, true);
        frame();
        frame();
        release();

        assertAll(
                () -> assertBounds(WINDOW_X, WINDOW_Y, WINDOW_W, WINDOW_H),
                () -> assertEquals(1, presses.get(), "the press belongs to the script's widget"));
    }

    @Test
    void aWindowResizedPastItsMinimum_stopsAtTheMinimum() {
        float right = WINDOW_X + WINDOW_W - INSIDE;
        float bottom = WINDOW_Y + WINDOW_H - INSIDE;

        hoverAt(right, bottom);
        press();
        mouse(right - COLLAPSE, bottom - COLLAPSE, true);
        frame();
        frame();
        release();

        assertBounds(WINDOW_X, WINDOW_Y, MIN_W, MIN_H);
    }

    @Test
    void aSecondResize_afterTheMouseLeftAndCameBack_resizesAgain() {
        float right = WINDOW_X + WINDOW_W - INSIDE;
        resizeRightEdge(right);
        hoverAt(CENTRE_X, CENTRE_Y);

        resizeRightEdge(right + MOVE_X);

        assertBounds(WINDOW_X, WINDOW_Y, WINDOW_W + 2 * MOVE_X, WINDOW_H);
    }

    @Test
    void aResize_thenABackgroundDrag_bothWork() {
        resizeRightEdge(WINDOW_X + WINDOW_W - INSIDE);

        hoverAt(EMPTY_X, EMPTY_Y);
        press();
        assertTrue(drag.isDragging(WINDOW));
        mouse(EMPTY_X + MOVE_X, EMPTY_Y + MOVE_Y, true);
        frame();
        frame();
        release();

        assertBounds(WINDOW_X + MOVE_X, WINDOW_Y + MOVE_Y, WINDOW_W + MOVE_X, WINDOW_H);
    }

    @Test
    void aBackgroundDrag_thenAResize_bothWork() {
        hoverAt(EMPTY_X, EMPTY_Y);
        press();
        mouse(EMPTY_X + MOVE_X, EMPTY_Y + MOVE_Y, true);
        frame();
        frame();
        release();

        float right = WINDOW_X + MOVE_X + WINDOW_W - INSIDE;
        hoverAt(right, CENTRE_Y + MOVE_Y);
        press();
        assertTrue(resize.isResizing(WINDOW));
        mouse(right + MOVE_X, CENTRE_Y + MOVE_Y, true);
        frame();
        frame();
        release();

        assertBounds(WINDOW_X + MOVE_X, WINDOW_Y + MOVE_Y, WINDOW_W + MOVE_X, WINDOW_H);
    }

    /** Grabs the right edge at {@code x} on the centre line and drags it {@link #MOVE_X} to the right. */
    private void resizeRightEdge(float x) {
        hoverAt(x, CENTRE_Y);
        assertEquals(ImGuiMouseCursor.ResizeEW, cursor, "hovering the right edge shows the resize cursor");
        press();
        assertTrue(resize.isResizing(WINDOW));
        mouse(x + MOVE_X, CENTRE_Y, true);
        frame();
        frame();
        release();
        assertFalse(resize.isResizing(WINDOW));
    }

    /** A UI that is one button over the whole panel, counting the presses that reach it. */
    private ScriptUI panelWideButton() {
        return () -> {
            ImGui.invisibleButton("everywhere", ImGui.getContentRegionAvailX(), ImGui.getContentRegionAvailY());
            if (ImGui.isItemActivated()) {
                presses.incrementAndGet();
            }
        };
    }

    /** Draws the window once, then moves the mouse to ({@code x}, {@code y}) and draws it again. */
    private void hoverAt(float x, float y) {
        frame();
        mouse(x, y, false);
        frame();
    }

    /** Presses the left button where the mouse is; the frame of the press is the last. */
    private void press() {
        ImGui.getIO().addMouseButtonEvent(ImGuiMouseButton.Left, true);
        frame();
    }

    /** Releases the left button and draws two more frames, so anything still following the mouse would show. */
    private void release() {
        ImGui.getIO().addMouseButtonEvent(ImGuiMouseButton.Left, false);
        frame();
        frame();
    }

    private void frame() {
        host.frame(() -> {
            ImGui.setNextWindowPos(WINDOW_X, WINDOW_Y, ImGuiCond.FirstUseEver);
            ImGui.setNextWindowSize(WINDOW_W, WINDOW_H, ImGuiCond.FirstUseEver);
            drag.beforeBegin(WINDOW);
            resize.beforeBegin(WINDOW, MIN_W, MIN_H);
            ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
            ImGui.begin(WINDOW, FRAMELESS);
            ImGui.popStyleVar();
            boolean isPointerTaken = resize.takesPointer(WINDOW, BORDER);
            wasBackgroundPress = scriptUis.draw(new ScriptUiSlot(OWNER, "script", ui, RUN), isPointerTaken);
            drag.afterDraw(WINDOW, wasBackgroundPress);
            windowX = ImGui.getWindowPosX();
            windowY = ImGui.getWindowPosY();
            windowW = ImGui.getWindowWidth();
            windowH = ImGui.getWindowHeight();
            ImGui.end();
        });
        cursor = ImGui.getMouseCursor();
        scriptUis.endFrame();
    }

    private void assertBounds(float x, float y, float width, float height) {
        assertAll(
                () -> assertEquals(x, windowX, EPSILON, "window x"),
                () -> assertEquals(y, windowY, EPSILON, "window y"),
                () -> assertEquals(width, windowW, EPSILON, "window width"),
                () -> assertEquals(height, windowH, EPSILON, "window height"));
    }

    private static void mouse(float x, float y, boolean isDown) {
        ImGuiIO io = ImGui.getIO();
        io.addMousePosEvent(x, y);
        io.addMouseButtonEvent(ImGuiMouseButton.Left, isDown);
    }
}
