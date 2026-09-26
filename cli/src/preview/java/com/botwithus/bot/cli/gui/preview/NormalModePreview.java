package com.botwithus.bot.cli.gui.preview;

import com.botwithus.bot.api.event.ConnectionLostEvent;
import com.botwithus.bot.api.event.ReconnectStateChangedEvent;
import com.botwithus.bot.api.event.ScriptCrashedEvent;
import com.botwithus.bot.api.event.ScriptLoadFailedEvent;
import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.cli.gui.AppMode;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.FontLoader;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Shell;
import com.botwithus.bot.cli.gui.notify.NotificationOverlay;
import com.botwithus.bot.cli.gui.usermode.PreviewSeams;
import com.botwithus.bot.cli.gui.usermode.UserModeRenderer;
import com.botwithus.bot.cli.gui.usermode.board.ClientView;
import com.botwithus.bot.cli.gui.usermode.board.SubscriptionGroup;
import com.botwithus.bot.core.impl.EventBusImpl;

import imgui.ImGui;
import imgui.app.Application;
import imgui.app.Configuration;
import imgui.flag.ImGuiConfigFlags;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import javax.imageio.ImageIO;

/**
 * DEV ONLY — never shipped. Renders Normal mode through the real {@link Shell}
 * with {@link FixtureBoard} data, one scenario after another, and writes a PNG of
 * each. Lives in the {@code preview} source set, which the application JAR, the
 * jlink image and the installer never include; run it with
 * {@code ./gradlew :cli:renderNormalModePreviews}.
 *
 * <p>Frames are drawn into an offscreen framebuffer before being read back, so a
 * window overlapping the preview cannot bleed into the capture. The real mouse is
 * parked off-screen every frame so hover states do not depend on where it is.</p>
 */
public final class NormalModePreview extends Application {

    private static final Logger log = LoggerFactory.getLogger(NormalModePreview.class);

    private static final int WIDTH = 1100;
    private static final int HEIGHT = 670;
    private static final float SCALE = 1f;
    private static final float ADVANCED_FONT_PX = 17f;
    /** Frames to let entrance, drawer and toast animations settle before capturing. */
    private static final int SETTLE_FRAMES = 45;
    private static final int RGBA = 4;
    private static final int BYTE_MASK = 0xFF;
    private static final int RED_SHIFT = 16;
    private static final int GREEN_SHIFT = 8;
    private static final float OFF_SCREEN = -Float.MAX_VALUE;
    private static final float BG = 0x0d / 255f;

    /** One captured state: which fixtures, and what to do on a given frame to reach it. */
    private record Scenario(String name, Supplier<FixtureBoard> board,
                            BiConsumer<Stage, Integer> onFrame) {}

    /** What a scenario's frame hook can reach. */
    private record Stage(FixtureBoard board, UserModeRenderer page, EventBusImpl bus) {}

    private final Path outDir;
    private final List<Scenario> scenarios = scenarios();
    private int scenarioIndex;
    private int frame;
    private Controls ui;
    private Stage stage;
    private Shell shell;
    private int fbo;

    private NormalModePreview(Path outDir) {
        this.outDir = outDir;
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "build/preview");
        Files.createDirectories(out);
        launch(new NormalModePreview(out));
    }

    @Override
    protected void configure(Configuration config) {
        config.setTitle("Normal mode preview (dev only)");
        config.setWidth(WIDTH);
        config.setHeight(HEIGHT);
    }

    @Override
    protected void initImGui(Configuration config) {
        super.initImGui(config);
        ImGui.getIO().setIniFilename(null);
        ImGui.getIO().addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);
        ui = new Controls(FontLoader.loadAll(SCALE, ADVANCED_FONT_PX));
        ImGuiTheme.apply(SCALE);
        fbo = createFramebuffer();
        startScenario();
    }

    private void startScenario() {
        Scenario s = scenarios.get(scenarioIndex);
        FixtureBoard board = s.board().get();
        UserModeRenderer page = new UserModeRenderer(ui);
        EventBusImpl bus = new EventBusImpl();
        NotificationOverlay toasts = new NotificationOverlay(Clock.systemDefaultZone(),
                name -> board.clients().stream().filter(c -> c.id().equals(name)).map(ClientView::account)
                        .findFirst().orElse(name));
        toasts.subscribeTo(bus);
        stage = new Stage(board, page, bus);
        shell = new Shell(ui, page, toasts);
        frame = 0;
    }

    @Override
    public void process() {
        ImGui.getIO().setMousePos(OFF_SCREEN, OFF_SCREEN);
        scenarios.get(scenarioIndex).onFrame().accept(stage, frame);
        shell.render(AppMode.NORMAL, stage.board(), () -> { }, n -> { });
        frame++;
    }

    @Override
    protected void endFrame() {
        ImGui.render();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glClearColor(BG, BG, BG, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        imGuiGl3.renderDrawData(ImGui.getDrawData());
        if (frame >= SETTLE_FRAMES) {
            capture(scenarios.get(scenarioIndex).name());
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        imGuiGl3.renderDrawData(ImGui.getDrawData());
        GLFW.glfwSwapBuffers(handle);
        GLFW.glfwPollEvents();
        if (frame >= SETTLE_FRAMES) {
            advance();
        }
    }

    private void advance() {
        scenarioIndex++;
        if (scenarioIndex >= scenarios.size()) {
            GLFW.glfwSetWindowShouldClose(handle, true);
            scenarioIndex = scenarios.size() - 1;
            return;
        }
        startScenario();
    }

    private static int createFramebuffer() {
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, WIDTH, HEIGHT, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, BufferUtils.createByteBuffer(WIDTH * HEIGHT * RGBA));
        int id = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, id);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Offscreen framebuffer incomplete: 0x" + Integer.toHexString(status));
        }
        return id;
    }

    private void capture(String name) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(WIDTH * HEIGHT * RGBA);
        GL11.glReadPixels(0, 0, WIDTH, HEIGHT, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < HEIGHT; y++) {
            int row = (HEIGHT - 1 - y) * WIDTH * RGBA;
            for (int x = 0; x < WIDTH; x++) {
                int i = row + x * RGBA;
                int rgb = (pixels.get(i) & BYTE_MASK) << RED_SHIFT
                        | (pixels.get(i + 1) & BYTE_MASK) << GREEN_SHIFT
                        | (pixels.get(i + 2) & BYTE_MASK);
                image.setRGB(x, y, rgb);
            }
        }
        Path file = outDir.resolve(name + ".png");
        try {
            ImageIO.write(image, "png", file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
        log.info("preview: wrote {}", file.toAbsolutePath());
    }

    // ── Scenarios ──────────────────────────────────────────────────────────

    private static final String WOODCUTTER = "BotWithUs_14208";
    private static final String IDLE = "BotWithUs_11820";

    private static List<Scenario> scenarios() {
        BiConsumer<Stage, Integer> nothing = (s, f) -> { };
        return List.of(
                new Scenario("01-clients-6-every-state", FixtureBoard::sixClients, nothing),
                new Scenario("02-clients-12", FixtureBoard::twelveClients, nothing),
                new Scenario("03-clients-12-needs-attention", FixtureBoard::twelveClients,
                        (s, f) -> PreviewSeams.showNeedsAttention(s.page())),
                new Scenario("04-waiting-for-client", FixtureBoard::waiting, nothing),
                new Scenario("05-host-offline", FixtureBoard::offline, nothing),
                new Scenario("06-script-picker", FixtureBoard::sixClients, (s, f) -> {
                    if (f == 0) {
                        s.page().openPicker(s.board(), IDLE);
                    }
                }),
                new Scenario("07-inspector-settings", FixtureBoard::sixClients, NormalModePreview::stagedEdits),
                new Scenario("08-inspector-script-ui", FixtureBoard::sixClients, (s, f) -> {
                    if (f == 0) {
                        s.page().openInspector(WOODCUTTER, true);
                    }
                }),
                new Scenario("09-toasts-failures", FixtureBoard::sixClients, NormalModePreview::failureToasts),
                new Scenario("10-toasts-reconnect", FixtureBoard::twelveClients, NormalModePreview::reconnectToasts),
                new Scenario("11-picker-subscriptions-installing", FixtureBoard::subscribed,
                        pickerOnRow(HERBLORE_INSTALLING_ROW)),
                new Scenario("12-picker-subscriptions-install-failed", FixtureBoard::subscribed,
                        pickerOnRow(RUNECRAFTING_FAILED_ROW)),
                new Scenario("13-picker-subscriptions-launcher-not-running",
                        () -> FixtureBoard.sixClients().withSubscriptions(new SubscriptionGroup.Unavailable(
                                SubscriptionGroup.Reason.LAUNCHER_NOT_RUNNING, "")),
                        pickerOnRow(0)),
                new Scenario("14-picker-old-launcher-no-group",
                        () -> FixtureBoard.sixClients().withSubscriptions(new SubscriptionGroup.Hidden()),
                        pickerOnRow(0)));
    }

    /** Row indices in {@link FixtureBoard#subscribed()}'s picker; subscriptions are listed by name, first. */
    private static final int HERBLORE_INSTALLING_ROW = 2;
    private static final int RUNECRAFTING_FAILED_ROW = 3;

    /** Opens the picker on the idle client and highlights {@code row}. */
    private static BiConsumer<Stage, Integer> pickerOnRow(int row) {
        return (s, f) -> {
            if (f == 0) {
                s.page().openPicker(s.board(), IDLE);
            } else if (f == 2) {
                PreviewSeams.highlightPickerRow(s.page(), row);
            }
        };
    }

    /** Opens the Woodcutting inspector and changes two fields, so the unsaved state shows. */
    private static void stagedEdits(Stage s, int f) {
        if (f == 0) {
            s.page().openInspector(WOODCUTTER, false);
        } else if (f == 2) {
            PreviewSeams.stageEdit(s.page(), "stopValue", 95);
            PreviewSeams.stageEdit(s.page(), "disposal", 2);
        }
    }

    private static void failureToasts(Stage s, int f) {
        if (f != 0) {
            return;
        }
        s.bus().publish(new ScriptLoadFailedEvent(Path.of("scripts", "woodcutting-1.0-SNAPSHOT.jar"),
                new IllegalStateException("missing module-info provides")));
        s.bus().publish(new ScriptCrashedEvent("Cook's Assistant", "BotWithUs_15002",
                new LastCrash(Phase.ON_LOOP, 0L, Instant.now(), new NullPointerException())));
        s.bus().publish(new ConnectionLostEvent("BotWithUs_10344", null));
    }

    private static void reconnectToasts(Stage s, int f) {
        if (f != 0) {
            return;
        }
        s.bus().publish(new ReconnectStateChangedEvent("BotWithUs_17388",
                new ReconnectState.GivingUp(0L, 5, new IllegalStateException("pipe gone"))));
        s.bus().publish(new ReconnectStateChangedEvent("BotWithUs_17388",
                new ReconnectState.Connected(0L)));
        s.bus().publish(new ReconnectStateChangedEvent("BotWithUs_17388",
                new ReconnectState.Reconnecting(0L, 2, 4000L)));
    }
}
