package com.botwithus.bot.cli.gui.scriptui;

import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.core.runtime.ScriptRunner;

import imgui.ImDrawData;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImVec2;
import imgui.flag.ImGuiMouseButton;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The script-author wiki's {@code ScriptUI} sample ({@link WikiSampleScript})
 * through the host's real script-UI path: a {@link ScriptUiSlot} keyed as the
 * inspector keys it, drawn by {@link ScriptUiRenderer} in a drawer-style panel,
 * so it renders in a sandbox and is composited into the host's frame. No game
 * and no script JAR: the sample's script object stands in for the loaded one.
 * Pointer input is ImGui IO events, in process.
 */
class ScriptUiWikiSampleTest {

    private static final String OWNER = "client/test/wiki-sample";
    private static final String LOCATOR_OWNER = "client/test/locator";
    private static final String LABEL = "wiki-sample";
    private static final String PANEL_ID = "##script-ui";
    private static final Instant RUN_STARTED = Instant.parse("2026-11-09T10:00:00Z");
    /** Where the drawer's panel starts in the host window; the sandbox's origin is here. */
    private static final float PANEL_X = 20f;
    private static final float PANEL_Y = 30f;
    private static final float PANEL_W = 400f;
    private static final float PANEL_H = 300f;
    /** The sample's three widgets, as the wiki writes them, for the reference UI and the locator. */
    private static final String STATUS_RUNNING = "Status: running";
    private static final String STATUS_STOPPED = "Status: stopped";
    private static final String BUTTON = "Do something";
    private static final String BAR_OVERLAY = "Progress";
    private static final float BAR_FULL_WIDTH = -1f;
    private static final float BAR_AUTO_HEIGHT = 0f;
    private static final float PROGRESS = 0.4f;
    private static final int RENDER_FRAMES = 10;
    /** Frames drawn before the one that is hashed, so the sandbox's first-frame layout is behind it. */
    private static final int WARM_UP_FRAMES = 3;
    private static final int FRAMES_AFTER_STOP = 30;
    private static final long STOP_TIMEOUT_MS = 5_000L;

    @Test
    void theSample_rendersOncePerHostFrame_andIsNeverStopped() {
        try (Rig rig = Rig.open()) {
            SandboxProbe probe = new SandboxProbe();
            ScriptUI ui = probe.around(new WikiSampleScript(PROGRESS).getUI(), BUTTON);
            for (int frame = 1; frame <= RENDER_FRAMES; frame++) {
                rig.frame(slot(ui, RUN_STARTED));
                assertEquals(frame, probe.completedRenders(), "render() returns once per host frame");
            }
            assertFalse(rig.state().isStopped(), "the sample breaks no Dear ImGui rule");
            assertEquals(0, rig.state().failures());
        }
    }

    @Test
    void theSample_drawsExactlyTheWikisThreeWidgets() {
        HostDrawChecksum sample = checksumOf(new WikiSampleScript(PROGRESS).getUI());
        HostDrawChecksum reference = checksumOf(reference(STATUS_RUNNING));
        HostDrawChecksum stopped = checksumOf(reference(STATUS_STOPPED));

        assertAll(
                () -> assertTrue(sample.indexBytes() > 0, "the host frame drew something"),
                () -> assertEquals(reference, sample, "the host frame is the three widgets' and nothing else"),
                () -> assertNotEquals(reference.crc(), stopped.crc(),
                        "negative control: other text must change the checksum, or it cannot see the text"));
    }

    @Test
    void theSamplesButton_isTheItemUnderThePointer_atItsPlaceInThePanel() {
        try (Rig rig = Rig.open()) {
            SandboxProbe probe = new SandboxProbe();
            ScriptUI ui = probe.around(new WikiSampleScript(PROGRESS).getUI(), BUTTON);
            ImVec2 inSandbox = buttonCentre(rig);
            Runnable frame = () -> rig.frame(slot(ui, RUN_STARTED));

            hover(PANEL_X + inSandbox.x, PANEL_Y + inSandbox.y, frame);
            SandboxProbe.Seen seen = probe.last().orElseThrow();

            assertAll(
                    () -> assertEquals(inSandbox.x, seen.mouseX(), "the panel's origin comes off the host's x"),
                    () -> assertEquals(inSandbox.y, seen.mouseY(), "the panel's origin comes off the host's y"),
                    () -> assertNotEquals(0, seen.watchedId()),
                    () -> assertEquals(seen.watchedId(), seen.hoveredId(),
                            "the item under the pointer is the button labelled \"" + BUTTON + "\""));
        }
    }

    @Test
    void aClick_onTheSamplesButton_runsItsClickBranchOnce() {
        try (Rig rig = Rig.open()) {
            WikiSampleScript script = new WikiSampleScript(PROGRESS);
            SandboxProbe probe = new SandboxProbe();
            ScriptUI ui = probe.around(script.getUI(), BUTTON);
            ImVec2 inSandbox = buttonCentre(rig);

            SandboxProbe.Seen pressed = click(PANEL_X + inSandbox.x, PANEL_Y + inSandbox.y,
                    () -> rig.frame(slot(ui, RUN_STARTED)), probe);

            assertAll(
                    () -> assertNotEquals(0, pressed.watchedId()),
                    () -> assertEquals(pressed.watchedId(), pressed.activeId(), "the press makes the button active"),
                    () -> assertEquals(1, script.clicks(), "the release runs the click branch, once"));
        }
    }

    /**
     * A real {@link ScriptRunner} is started and stopped. The panel's slot is
     * rebuilt every frame from the runner, as {@code LiveInspectorSource} builds
     * it: the script's {@code getUI()} and the runner's {@code lastStartedAt()}.
     * A stop does not move {@code lastStartedAt()} (only a start does), so the
     * renderer sees the same run and keeps drawing the UI.
     */
    @Test
    void afterTheScriptStops_thePanelKeepsDrawingIt_andItsButtonStillClicks() {
        WikiSampleScript script = new WikiSampleScript(PROGRESS);
        ScriptRunner runner = new ScriptRunner(script, mock(ScriptContext.class));
        try (Rig rig = Rig.open()) {
            SandboxProbe probe = new SandboxProbe();
            Runnable frame = () -> rig.frame(slot(probe.around(runner.getScript().getUI(), BUTTON),
                    runner.lastStartedAt()));
            ImVec2 inSandbox = buttonCentre(rig);
            float hostX = PANEL_X + inSandbox.x;
            float hostY = PANEL_Y + inSandbox.y;
            runner.start();
            click(hostX, hostY, frame, probe);
            assertEquals(1, script.clicks(), "a click while the script runs");

            runner.stop();
            assertTrue(runner.awaitStop(STOP_TIMEOUT_MS), "the script's runner stops");
            int rendersAtStop = probe.completedRenders();
            for (int i = 0; i < FRAMES_AFTER_STOP; i++) {
                frame.run();
            }
            int rendersAfterStop = probe.completedRenders() - rendersAtStop;
            click(hostX, hostY, frame, probe);

            assertAll(
                    () -> assertFalse(runner.isRunning()),
                    () -> assertEquals(FRAMES_AFTER_STOP, rendersAfterStop,
                            "the panel keeps drawing the UI, once a frame, after its script stops"),
                    () -> assertFalse(rig.state().isStopped(), "a stopped script is not a broken UI"),
                    () -> assertEquals(0, rig.state().failures()),
                    () -> assertEquals(2, script.clicks(), "a click after the stop still reaches the handler"));
        } finally {
            runner.stop();
        }
    }

    /** The wiki's three widgets, written independently of the sample, with {@code status} as the text. */
    private static ScriptUI reference(String status) {
        return () -> {
            ImGui.text(status);
            ImGui.button(BUTTON);
            ImGui.progressBar(PROGRESS, BAR_FULL_WIDTH, BAR_AUTO_HEIGHT, BAR_OVERLAY);
        };
    }

    /** The checksum of the host frame after {@link #WARM_UP_FRAMES}, in a host of its own. */
    private static HostDrawChecksum checksumOf(ScriptUI ui) {
        try (Rig rig = Rig.open()) {
            for (int i = 0; i < WARM_UP_FRAMES; i++) {
                rig.frame(slot(ui, RUN_STARTED));
            }
            return HostDrawChecksum.of(rig.frame(slot(ui, RUN_STARTED)));
        }
    }

    /**
     * Where the button's centre falls in sandbox coordinates, to the pixel, found by drawing
     * the reference widgets once in a sandbox of their own and reading the
     * button's rect. Independent of the sample, so a sample that moved or
     * relabelled its button would not be hovered here.
     */
    private static ImVec2 buttonCentre(Rig rig) {
        AtomicReference<ImVec2> centre = new AtomicReference<>();
        ScriptUI locator = () -> {
            ImGui.text(STATUS_RUNNING);
            ImGui.button(BUTTON);
            centre.set(new ImVec2(wholePixel(ImGui.getItemRectMinX(), ImGui.getItemRectMaxX()),
                    wholePixel(ImGui.getItemRectMinY(), ImGui.getItemRectMaxY())));
            ImGui.progressBar(PROGRESS, BAR_FULL_WIDTH, BAR_AUTO_HEIGHT, BAR_OVERLAY);
        };
        rig.frame(new ScriptUiSlot(LOCATOR_OWNER, LABEL, locator, RUN_STARTED));
        return centre.get();
    }

    /** The whole pixel at the middle of {@code min..max}: Dear ImGui floors every pointer position it is given. */
    private static float wholePixel(float min, float max) {
        return (float) Math.floor((min + max) / 2f);
    }

    /** The pointer at ({@code x}, {@code y}) for two frames: Dear ImGui hovers windows from the frame before. */
    private static void hover(float x, float y, Runnable frame) {
        mouse(x, y, false);
        frame.run();
        mouse(x, y, false);
        frame.run();
    }

    /** Hover, press, release at ({@code x}, {@code y}). Returns what the sandbox saw on the press frame. */
    private static SandboxProbe.Seen click(float x, float y, Runnable frame, SandboxProbe probe) {
        hover(x, y, frame);
        mouse(x, y, true);
        frame.run();
        SandboxProbe.Seen pressed = probe.last().orElseThrow();
        mouse(x, y, false);
        frame.run();
        return pressed;
    }

    private static void mouse(float x, float y, boolean isDown) {
        ImGuiIO io = ImGui.getIO();
        io.addMousePosEvent(x, y);
        io.addMouseButtonEvent(ImGuiMouseButton.Left, isDown);
    }

    private static ScriptUiSlot slot(ScriptUI ui, Instant startedAt) {
        return new ScriptUiSlot(OWNER, LABEL, ui, startedAt);
    }

    /** A windowless host and its script-UI renderer, closed in the order the host closes them. */
    private record Rig(HeadlessHost host, ScriptUiRenderer renderer) implements AutoCloseable {

        static Rig open() {
            HeadlessHost host = HeadlessHost.create();
            return new Rig(host, new ScriptUiRenderer(AssertRouter.install(), new TypedChars(), () -> false));
        }

        /** One host frame with the slot drawn in the drawer's panel; endFrame where the shell calls it. */
        ImDrawData frame(ScriptUiSlot slot) {
            return host.frame(() -> HeadlessHost.panel(PANEL_ID, PANEL_X, PANEL_Y, PANEL_W, PANEL_H,
                    () -> renderer.draw(slot)), () -> { }, renderer::endFrame);
        }

        SlotState state() {
            return renderer.state(OWNER).orElseThrow();
        }

        @Override
        public void close() {
            renderer.close();
            host.close();
        }
    }
}
