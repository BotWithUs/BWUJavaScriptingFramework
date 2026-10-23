package com.botwithus.bot.cli.gui.scriptui;

import com.botwithus.bot.api.ui.ScriptUI;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiMouseButton;
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
 * The host's own widgets while a script UI holds the host's keyboard: the
 * hold is there only while a script text field is active, and a press
 * anywhere outside the panel lets go of it, so that very press lands on the
 * host and the script's field loses focus. Real imgui-java natives, with host
 * buttons submitted before and after the panel, since a held active item
 * blocks both. Focus that leaves the panel without a press, and a panel that is
 * no longer drawn, end the typing too.
 */
class HostInputWhileTypingTest {

    private static final String OWNER = "client/test/script";
    private static final Instant RUN = Instant.parse("2026-10-21T10:00:00Z");
    private static final float PANEL_X = 20f;
    private static final float PANEL_Y = 30f;
    private static final float PANEL_W = 400f;
    private static final float PANEL_H = 300f;
    /** A point inside the first widget the test UIs draw, in host screen coordinates. */
    private static final float FIELD_X = PANEL_X + 10f;
    private static final float FIELD_Y = PANEL_Y + 8f;
    /**
     * A point inside the field past the end of its text, far enough from
     * {@link #FIELD_X} that a click there is not a double click.
     */
    private static final float FIELD_END_X = PANEL_X + 100f;
    /** A point on the panel where the test UIs draw nothing. */
    private static final float EMPTY_X = PANEL_X + 250f;
    private static final float EMPTY_Y = PANEL_Y + 200f;
    /** Where the host button submitted before the panel sits. */
    private static final float BEFORE_X = 20f;
    private static final float BEFORE_Y = 500f;
    /** Where the host button submitted after the panel sits. */
    private static final float AFTER_X = 20f;
    private static final float AFTER_Y = 600f;
    /** How far inside a host button a click lands. */
    private static final float INTO_BUTTON = 6f;
    /** A host window clear of the panel and the host buttons. */
    private static final float OTHER_X = 600f;
    private static final float OTHER_Y = 400f;
    private static final float OTHER_W = 200f;
    private static final float OTHER_H = 100f;
    private static final int TEXT_CAPACITY = 32;

    private final ImString text = new ImString(TEXT_CAPACITY);
    private final AtomicBoolean isFieldActive = new AtomicBoolean();
    private final AtomicInteger scriptClicks = new AtomicInteger();
    private final AtomicInteger beforeClicks = new AtomicInteger();
    private final AtomicInteger afterClicks = new AtomicInteger();
    /** A text field at the panel's top-left. */
    private ScriptUI ui = () -> {
        ImGui.inputText("##field", text);
        isFieldActive.set(ImGui.isItemActive());
    };
    /** Host windows drawn at the top level, after the host window. */
    private Runnable windows = () -> { };
    private boolean isPanelDrawn = true;
    private HeadlessHost host;
    private TypedChars typed;
    private ScriptUiRenderer scriptUis;

    @BeforeEach
    void setUp() {
        host = HeadlessHost.create();
        typed = new TypedChars();
        scriptUis = new ScriptUiRenderer(AssertRouter.install(), typed, () -> false);
    }

    @AfterEach
    void tearDown() {
        scriptUis.close();
        host.close();
    }

    @Test
    void aClickOnAHostButtonBeforeThePanel_landsFirstTime_whileAScriptFieldIsActive() {
        startTyping();

        click(BEFORE_X + INTO_BUTTON, BEFORE_Y + INTO_BUTTON);

        assertEquals(1, beforeClicks.get(), "the first click must reach the host's button");
        assertFalse(scriptUis.hasKeyboard());
    }

    @Test
    void aClickOnAHostButtonAfterThePanel_landsFirstTime_whileAScriptFieldIsActive() {
        startTyping();

        click(AFTER_X + INTO_BUTTON, AFTER_Y + INTO_BUTTON);

        assertEquals(1, afterClicks.get(), "the first click must reach the host's button");
        assertFalse(scriptUis.hasKeyboard());
    }

    @Test
    void aClickOutsideThePanel_takesTheFocusFromTheScriptsField() {
        startTyping();

        click(BEFORE_X + INTO_BUTTON, BEFORE_Y + INTO_BUTTON);
        typed.typed('x');
        frame();
        frame();

        assertAll(
                () -> assertFalse(isFieldActive.get(), "the field must see the focus go"),
                () -> assertEquals("h", text.get(), "typing after the click is not the field's"));
    }

    @Test
    void aClickOnTheScriptsField_keepsTheHold_andTheTyping() {
        startTyping();

        click(FIELD_END_X, FIELD_Y);
        typed.typed('i');
        frame();
        frame();

        assertAll(
                () -> assertTrue(scriptUis.hasKeyboard()),
                () -> assertTrue(isFieldActive.get()),
                () -> assertEquals("hi", text.get()));
    }

    @Test
    void aClickOnThePanelsBackground_isTheScripts_andEndsItsTyping() {
        startTyping();

        click(EMPTY_X, EMPTY_Y);

        assertAll(
                () -> assertFalse(isFieldActive.get()),
                () -> assertFalse(scriptUis.hasKeyboard()),
                () -> assertEquals(0, beforeClicks.get() + afterClicks.get()));
    }

    @Test
    void aScriptButtonWithKeyboardNavigation_holdsNothing_andTheHostStaysClickable() {
        ui = () -> {
            if (ImGui.button("Press me")) {
                scriptClicks.incrementAndGet();
            }
        };

        frame();
        click(FIELD_X, FIELD_Y);
        assertEquals(1, scriptClicks.get());
        assertFalse(scriptUis.hasKeyboard(), "keyboard navigation is not typing");

        click(BEFORE_X + INTO_BUTTON, BEFORE_Y + INTO_BUTTON);
        assertEquals(1, beforeClicks.get(), "the first click must reach the host's button");
    }

    @Test
    void aHostWindowTakingTheFocus_withoutAPress_endsTheScriptsTyping() {
        startTyping();

        windows = () -> {
            ImGui.setNextWindowFocus();
            HeadlessHost.window("Other", OTHER_X, OTHER_Y, OTHER_W, OTHER_H, () -> { });
        };
        frame();
        windows = () -> HeadlessHost.window("Other", OTHER_X, OTHER_Y, OTHER_W, OTHER_H, () -> { });
        typed.typed('x');
        frame();
        frame();

        assertAll(
                () -> assertFalse(isFieldActive.get(), "the field must see the focus go"),
                () -> assertFalse(scriptUis.hasKeyboard()),
                () -> assertEquals("h", text.get(), "typing after the focus went is not the field's"));
    }

    @Test
    void aPanelNoLongerDrawn_whileTyping_holdsNothing() {
        startTyping();

        isPanelDrawn = false;
        frame();

        assertFalse(scriptUis.hasKeyboard(), "a UI that is not drawn is not typing");
    }

    /** Clicks the field and types one character into it. */
    private void startTyping() {
        click(FIELD_X, FIELD_Y);
        typed.typed('h');
        frame();
        frame();
        assertEquals("h", text.get());
        assertTrue(scriptUis.hasKeyboard(), "precondition: the script's field holds the host's keyboard");
    }

    /**
     * Hovers ({@code x}, {@code y}) for a frame, then presses and releases the
     * left button there. One frame more at the end: the sandbox gets the mouse
     * as events, and Dear ImGui trickles a press and its release over two frames.
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
        host.frame(() -> {
            ImGui.setCursorScreenPos(BEFORE_X, BEFORE_Y);
            if (ImGui.button("Before")) {
                beforeClicks.incrementAndGet();
            }
            if (isPanelDrawn) {
                HeadlessHost.panel("##script-ui", PANEL_X, PANEL_Y, PANEL_W, PANEL_H,
                        () -> scriptUis.draw(new ScriptUiSlot(OWNER, "script", ui, RUN)));
            }
            ImGui.setCursorScreenPos(AFTER_X, AFTER_Y);
            if (ImGui.button("After")) {
                afterClicks.incrementAndGet();
            }
        }, windows, () -> { });
        scriptUis.endFrame();
    }

    private static void mouse(float x, float y, boolean isDown) {
        ImGuiIO io = ImGui.getIO();
        io.addMousePosEvent(x, y);
        io.addMouseButtonEvent(ImGuiMouseButton.Left, isDown);
    }
}
