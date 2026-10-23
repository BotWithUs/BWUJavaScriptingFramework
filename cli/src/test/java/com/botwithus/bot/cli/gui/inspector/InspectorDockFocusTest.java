package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.HostIni;
import com.botwithus.bot.cli.gui.Motion;
import com.botwithus.bot.cli.gui.UiFonts;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ClientScript;
import com.botwithus.bot.cli.gui.nav.PageId;
import com.botwithus.bot.cli.gui.scriptui.AssertRouter;
import com.botwithus.bot.cli.gui.scriptui.ScriptUiRenderer;
import com.botwithus.bot.cli.gui.scriptui.TypedChars;
import com.botwithus.bot.cli.gui.usermode.board.ScriptInfo;

import imgui.ImFont;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiBackendFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.internal.ImGuiContext;
import imgui.type.ImInt;
import imgui.type.ImString;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real drawer, headless: {@link InspectorDock} and its {@link ConfigInspector}
 * on the Script UI tab, drawn in the order the shell draws them, beside a page
 * with a Start button on it. What a user does in the drawer must leave the
 * page's buttons, and the drawer's Esc, working.
 */
class InspectorDockFocusTest {

    private static final float WIDTH = 1200f;
    private static final float HEIGHT = 800f;
    private static final float FRAME_SECONDS = 1f / 60f;
    /** Any non-zero id: there is no renderer to bind it. */
    private static final long FONT_TEXTURE = 7L;
    private static final int HOST_FLAGS = ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoBackground
            | ImGuiWindowFlags.NoSavedSettings;
    private static final ClientScript SUBJECT = new ClientScript(TestScripts.PIPE, "Example Script");
    /** Long enough for the drawer to finish sliding open. */
    private static final int SETTLE_FRAMES = 90;
    private static final int IDLE_FRAMES = 3;
    /** The page's Start button, top left of the page. */
    private static final float START_X = 20f;
    private static final float START_Y = 20f;
    private static final float ON_START_X = START_X + 10f;
    private static final float ON_START_Y = START_Y + 8f;
    /** Low in the drawer, where the script's UI fills the Script UI tab. */
    private static final float IN_DRAWER_FROM_RIGHT = 100f;
    private static final float IN_DRAWER_FROM_BOTTOM = 100f;
    private static final int TEXT_CAPACITY = 32;

    private ImGuiContext context;
    private Controls ui;
    private ScriptUiRenderer scriptUis;
    private TypedChars typed;
    private InspectorState state;
    private InspectorDock dock;
    private final AtomicInteger starts = new AtomicInteger();

    @BeforeEach
    void setUp() {
        context = ImGui.createContext();
        ImGuiIO io = ImGui.getIO();
        HostIni.attach(Path.of("build", "inspector-dock-focus-never-written.ini"));
        io.setDisplaySize(WIDTH, HEIGHT);
        io.setDeltaTime(FRAME_SECONDS);
        io.setBackendFlags(ImGuiBackendFlags.RendererHasVtxOffset);
        ImFont font = io.getFonts().addFontDefault();
        io.getFonts().getTexDataAsRGBA32(new ImInt(), new ImInt());
        io.getFonts().setTexID(FONT_TEXTURE);
        ui = new Controls(new UiFonts(font, font, font, font, font, font, font, font, font),
                Motion.full(Motion.FrameClock.imGui()));
        typed = new TypedChars();
        scriptUis = ScriptUiRenderer.withControls(AssertRouter.install(), typed, ui);
        state = new InspectorState(InstantSource.fixed(TestScripts.T0));
    }

    @AfterEach
    void tearDown() {
        scriptUis.close();
        ImGui.destroyContext(context);
    }

    @Test
    void aPressOnTheScriptsButtonInTheDrawer_leavesThePagesStartButtonWorking() {
        AtomicInteger scriptClicks = new AtomicInteger();
        open(() -> {
            if (ImGui.button("Press me", -1f, -1f)) {
                scriptClicks.incrementAndGet();
            }
        });

        click(WIDTH - IN_DRAWER_FROM_RIGHT, HEIGHT - IN_DRAWER_FROM_BOTTOM);
        idle();
        click(ON_START_X, ON_START_Y);

        assertEquals(1, scriptClicks.get(), "the press landed on the script's button in the drawer");
        assertEquals(1, starts.get(), "the page's Start button must take the next click");
    }

    @Test
    void aPressOnTheScriptsButtonInTheDrawer_leavesEscClosingTheDrawer() {
        AtomicInteger scriptClicks = new AtomicInteger();
        open(() -> {
            if (ImGui.button("Press me", -1f, -1f)) {
                scriptClicks.incrementAndGet();
            }
        });

        click(WIDTH - IN_DRAWER_FROM_RIGHT, HEIGHT - IN_DRAWER_FROM_BOTTOM);
        idle();
        key(ImGuiKey.Escape);

        assertEquals(1, scriptClicks.get(), "the press landed on the script's button in the drawer");
        assertFalse(state.isOpen(), "Esc closes the drawer once nothing is being typed");
    }

    @Test
    void whileTypingInTheDrawer_escIsTheScriptsAndAPressOnStartEndsTheTyping() {
        ImString text = new ImString(TEXT_CAPACITY);
        open(() -> ImGui.inputTextMultiline("##notes", text, -1f, -1f));

        click(WIDTH - IN_DRAWER_FROM_RIGHT, HEIGHT - IN_DRAWER_FROM_BOTTOM);
        typed.typed('h');
        frame();
        assertEquals("h", text.get(), "the press landed in the script's field");
        assertTrue(scriptUis.hasKeyboard());

        key(ImGuiKey.Escape);
        assertTrue(state.isOpen(), "Esc while typing is the script's, not the drawer's");

        click(WIDTH - IN_DRAWER_FROM_RIGHT, HEIGHT - IN_DRAWER_FROM_BOTTOM);
        click(ON_START_X, ON_START_Y);
        idle();

        assertEquals(1, starts.get(), "the page's Start button takes a click while the script's field is active");
        assertFalse(scriptUis.hasKeyboard(), "and the script's typing ends with it");
    }

    /** Opens the drawer on the Script UI tab of a script that draws {@code scriptUi}, and lets it slide fully open. */
    private void open(ScriptUI scriptUi) {
        ScriptInfo info = new ScriptInfo(SUBJECT.scriptName(), "", "", ScriptCategory.OTHER, "", 0, true);
        InspectorTarget target = new InspectorTarget(SUBJECT, "", info, List.of(),
                () -> new ScriptConfig(Map.of()), cfg -> { }, scriptUi, id -> Optional.empty(), () -> false);
        dock = new InspectorDock(ui, state, subject -> Optional.of(target), scriptUis);
        state.open(SUBJECT, InspectorTab.SCRIPT_UI);
        mouse(WIDTH / 2f, HEIGHT / 2f, false);
        for (int i = 0; i < SETTLE_FRAMES; i++) {
            frame();
        }
    }

    /** One frame in the shell's order: page and drawer in the host window, pop-outs, then the renderer's end. */
    private void frame() {
        ImGui.newFrame();
        ImGui.setNextWindowPos(0f, 0f);
        ImGui.setNextWindowSize(WIDTH, HEIGHT);
        ImGui.begin("host", HOST_FLAGS);
        float availW = ImGui.getContentRegionAvailX();
        float h = ImGui.getContentRegionAvailY();
        float drawerW = dock.beginFrame(PageId.CLIENTS, availW);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.beginChild("##page", availW - drawerW, h, false);
        ImGui.popStyleVar();
        ImGui.setCursorScreenPos(START_X, START_Y);
        if (ImGui.button("Start")) {
            starts.incrementAndGet();
        }
        ImGui.endChild();
        if (drawerW > 0f) {
            ImGui.sameLine(0f, 0f);
            dock.render(drawerW, availW, h);
        }
        ImGui.end();
        dock.renderWindows();
        scriptUis.endFrame();
        ImGui.render();
    }

    private void idle() {
        for (int i = 0; i < IDLE_FRAMES; i++) {
            frame();
        }
    }

    /** Hover, press, release at ({@code x}, {@code y}), after a frame with the mouse already there. */
    private void click(float x, float y) {
        mouse(x, y, false);
        frame();
        frame();
        mouse(x, y, true);
        frame();
        mouse(x, y, false);
        frame();
    }

    private void key(int key) {
        ImGui.getIO().addKeyEvent(key, true);
        frame();
        ImGui.getIO().addKeyEvent(key, false);
        frame();
        idle();
    }

    private static void mouse(float x, float y, boolean isDown) {
        ImGuiIO io = ImGui.getIO();
        io.addMousePosEvent(x, y);
        io.addMouseButtonEvent(ImGuiMouseButton.Left, isDown);
    }
}
