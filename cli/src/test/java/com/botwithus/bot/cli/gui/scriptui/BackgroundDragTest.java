package com.botwithus.bot.cli.gui.scriptui;

import com.botwithus.bot.api.ui.ScriptUI;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiWindowFlags;
import imgui.internal.flag.ImGuiButtonFlags;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A frameless window around a sandboxed script UI, the way a pop-out that
 * draws its own chrome is drawn: a drag on the UI's background moves the
 * window, a drag on one of its widgets is the script's and moves nothing.
 * Real imgui-java natives, no viewports.
 */
class BackgroundDragTest {

    private static final String WINDOW = "frameless##test";
    private static final String OWNER = "client/test/script";
    private static final Instant RUN = Instant.parse("2026-10-20T10:00:00Z");
    private static final float WINDOW_X = 100f;
    private static final float WINDOW_Y = 100f;
    private static final float WINDOW_W = 400f;
    private static final float WINDOW_H = 300f;
    /** A point in the window's lower half, where the test UI draws nothing. */
    private static final float EMPTY_X = WINDOW_X + 250f;
    private static final float EMPTY_Y = WINDOW_Y + 200f;
    /** A point inside the button the test UI draws first, at the panel's top-left. */
    private static final float BUTTON_X = WINDOW_X + 20f;
    private static final float BUTTON_Y = WINDOW_Y + 16f;
    private static final float MOVE_X = 50f;
    private static final float MOVE_Y = 30f;
    /** Window positions are floats moved by whole pixels; this is far below one. */
    private static final float EPSILON = 0.01f;
    /** The size of the release-only widget, enough to cover {@link #BUTTON_X}, {@link #BUTTON_Y}. */
    private static final float WIDGET_SIZE = 60f;
    /**
     * A widget that is hovered by a press but does not become the active item
     * until the button is released, so only the hovered-item check can tell
     * that press from one on the background.
     */
    private static final int ON_RELEASE = ImGuiButtonFlags.PressedOnRelease | ImGuiButtonFlags.MouseButtonLeft;
    private static final int FRAMELESS = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoBackground
            | ImGuiWindowFlags.NoSavedSettings;

    private final AtomicInteger clicks = new AtomicInteger();
    private ScriptUI ui = () -> {
        if (ImGui.button("Press me")) {
            clicks.incrementAndGet();
        }
    };
    private HeadlessHost host;
    private ScriptUiRenderer scriptUis;
    private BackgroundDrag drag;
    private float windowX;
    private float windowY;
    private boolean wasBackgroundPress;

    @BeforeEach
    void setUp() {
        host = HeadlessHost.create();
        scriptUis = new ScriptUiRenderer(AssertRouter.install(), new TypedChars(), () -> false);
        drag = new BackgroundDrag();
    }

    @AfterEach
    void tearDown() {
        scriptUis.close();
        host.close();
    }

    @Test
    void aDragOnTheBackground_movesTheWindow_untilTheButtonIsReleased() {
        pressAt(EMPTY_X, EMPTY_Y);
        assertTrue(wasBackgroundPress, "a press on empty space must be reported");
        assertTrue(drag.isDragging(WINDOW));

        mouse(EMPTY_X + MOVE_X, EMPTY_Y + MOVE_Y, true);
        frame();
        frame();
        assertPosition(WINDOW_X + MOVE_X, WINDOW_Y + MOVE_Y);

        mouse(EMPTY_X + MOVE_X, EMPTY_Y + MOVE_Y, false);
        frame();
        mouse(EMPTY_X + MOVE_X * 2f, EMPTY_Y + MOVE_Y * 2f, false);
        frame();

        assertFalse(drag.isDragging(WINDOW));
        assertPosition(WINDOW_X + MOVE_X, WINDOW_Y + MOVE_Y);
        assertEquals(0, clicks.get());
    }

    @Test
    void aDragOnAWidget_reachesTheScript_andDoesNotMoveTheWindow() {
        pressAt(BUTTON_X, BUTTON_Y);
        assertFalse(wasBackgroundPress, "a press on a widget is the script's");

        mouse(BUTTON_X + MOVE_X, BUTTON_Y + MOVE_Y, true);
        frame();
        frame();
        assertPosition(WINDOW_X, WINDOW_Y);

        mouse(BUTTON_X, BUTTON_Y, true);
        frame();
        mouse(BUTTON_X, BUTTON_Y, false);
        frame();

        assertFalse(drag.isDragging(WINDOW));
        assertPosition(WINDOW_X, WINDOW_Y);
        assertEquals(1, clicks.get(), "the click must still reach the script's button");
    }

    @Test
    void aPressOnAHoveredWidgetThatIsNotYetActive_doesNotMoveTheWindow() {
        ui = () -> ImGui.invisibleButton("on-release", WIDGET_SIZE, WIDGET_SIZE, ON_RELEASE);

        pressAt(BUTTON_X, BUTTON_Y);
        assertFalse(wasBackgroundPress, "a press on a hovered widget is the script's");

        mouse(BUTTON_X + MOVE_X, BUTTON_Y + MOVE_Y, true);
        frame();
        frame();

        assertFalse(drag.isDragging(WINDOW));
        assertPosition(WINDOW_X, WINDOW_Y);
    }

    /** Hovers, then presses the left button at ({@code x}, {@code y}); the frame of the press is the last. */
    private void pressAt(float x, float y) {
        frame();
        mouse(x, y, false);
        frame();
        mouse(x, y, true);
        frame();
    }

    private void frame() {
        host.frame(() -> {
            ImGui.setNextWindowPos(WINDOW_X, WINDOW_Y, ImGuiCond.FirstUseEver);
            ImGui.setNextWindowSize(WINDOW_W, WINDOW_H, ImGuiCond.FirstUseEver);
            drag.beforeBegin(WINDOW);
            ImGui.begin(WINDOW, FRAMELESS);
            wasBackgroundPress = scriptUis.draw(new ScriptUiSlot(OWNER, "script", ui, RUN));
            drag.afterDraw(WINDOW, wasBackgroundPress);
            windowX = ImGui.getWindowPosX();
            windowY = ImGui.getWindowPosY();
            ImGui.end();
        });
        scriptUis.endFrame();
    }

    private void assertPosition(float x, float y) {
        assertAll(
                () -> assertEquals(x, windowX, EPSILON, "window x"),
                () -> assertEquals(y, windowY, EPSILON, "window y"));
    }

    private static void mouse(float x, float y, boolean isDown) {
        ImGuiIO io = ImGui.getIO();
        io.addMousePosEvent(x, y);
        io.addMouseButtonEvent(ImGuiMouseButton.Left, isDown);
    }
}
