package com.botwithus.bot.cli.gui.scriptui;

import com.botwithus.bot.api.ui.ScriptUI;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImString;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
