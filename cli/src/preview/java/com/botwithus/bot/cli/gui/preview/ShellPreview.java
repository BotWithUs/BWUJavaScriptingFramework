package com.botwithus.bot.cli.gui.preview;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.event.ConnectionLostEvent;
import com.botwithus.bot.api.event.ReconnectStateChangedEvent;
import com.botwithus.bot.api.event.ScriptCrashedEvent;
import com.botwithus.bot.api.event.ScriptLoadFailedEvent;
import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.gui.AppMode;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.FontLoader;
import com.botwithus.bot.cli.gui.FramelessChrome;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Shell;
import com.botwithus.bot.cli.gui.inspector.InspectorDock;
import com.botwithus.bot.cli.gui.inspector.InspectorPreviewSeams;
import com.botwithus.bot.cli.gui.inspector.InspectorRequest;
import com.botwithus.bot.cli.gui.inspector.InspectorState;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject;
import com.botwithus.bot.cli.gui.inspector.InspectorTab;
import com.botwithus.bot.cli.gui.nav.PageId;
import com.botwithus.bot.cli.gui.pages.connections.ConnectionsPreviewSeams;
import com.botwithus.bot.cli.gui.pages.connections.RowFilter;
import com.botwithus.bot.cli.gui.pages.dashboard.DashboardPreviewSeams;
import com.botwithus.bot.cli.gui.pages.dashboard.DashboardState;
import com.botwithus.bot.cli.gui.pages.dashboard.DashboardState.DockTab;
import com.botwithus.bot.cli.gui.pages.dashboard.DashboardState.RunnerFilter;
import com.botwithus.bot.cli.gui.preview.FixtureDashboardModel.Fleet;
import com.botwithus.bot.cli.gui.notify.NotificationOverlay;
import com.botwithus.bot.cli.gui.pages.store.PriceFilter;
import com.botwithus.bot.cli.gui.pages.store.StorePage;
import com.botwithus.bot.cli.gui.pages.store.StorePreviewSeams;
import com.botwithus.bot.cli.gui.pages.store.StoreQuery;
import com.botwithus.bot.cli.gui.pages.store.StoreTab;
import com.botwithus.bot.cli.gui.preview.FixtureFleet.RestartStep;
import com.botwithus.bot.cli.gui.usermode.PreviewSeams;
import com.botwithus.bot.cli.gui.usermode.UserModeRenderer;
import com.botwithus.bot.cli.gui.usermode.board.SubscriptionGroup;
import com.botwithus.bot.cli.gui.window.WindowRect;
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
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

/**
 * DEV ONLY — never shipped. Renders both modes through the real {@link Shell}
 * with {@link FixtureBoard} data and the {@link FixturePages} page set, one
 * scenario after another, and writes a PNG of each. Lives in the
 * {@code preview} source set, which the application JAR, the jlink image and the
 * installer never include; run it with {@code ./gradlew :cli:renderPreviews}.
 *
 * <p>Frames are drawn into an offscreen framebuffer before being read back, so a
 * window overlapping the preview cannot bleed into the capture. The real mouse is
 * parked off-screen every frame so hover states do not depend on where it is.</p>
 */
public final class ShellPreview extends Application {

    private static final Logger log = LoggerFactory.getLogger(ShellPreview.class);

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
    /** The frameless chrome's resize floor; nothing is resized in a capture. */
    private static final int MIN_WINDOW_WIDTH = 640;
    private static final int MIN_WINDOW_HEIGHT = 400;

    /**
     * One captured state: the mode, which fixtures, and what to do on a given
     * frame to reach it.
     */
    private record Scenario(String name, AppMode mode, Supplier<FixtureBoard> board,
                            Supplier<FixtureStoreModel> store, Supplier<FixtureConnectionsModel> connections,
                            BiConsumer<Stage, Integer> onFrame) {

        Scenario(String name, AppMode mode, Supplier<FixtureBoard> board, Supplier<FixtureStoreModel> store,
                 BiConsumer<Stage, Integer> onFrame) {
            this(name, mode, board, store, FixtureConnectionsModel::busy, onFrame);
        }

        Scenario(String name, Supplier<FixtureBoard> board, BiConsumer<Stage, Integer> onFrame) {
            this(name, AppMode.NORMAL, board, FixtureStoreModel::signedIn, onFrame);
        }

        static Scenario advanced(String name, Supplier<FixtureBoard> board, BiConsumer<Stage, Integer> onFrame) {
            return new Scenario(name, AppMode.ADVANCED, board, FixtureStoreModel::signedIn, onFrame);
        }

        /** The Script Store page over {@code store}, with {@code onFrame} standing in for clicks. */
        static Scenario store(String name, Supplier<FixtureStoreModel> store, BiConsumer<StorePage, Integer> onFrame) {
            return new Scenario(name, AppMode.ADVANCED, FixtureBoard::everyState, store, (s, f) -> {
                s.pages().registry().select(PageId.STORE);
                onFrame.accept(s.pages().store(), f);
            });
        }

        /** Advanced mode on the Connections page over {@code model}, then {@code onFrame}. */
        static Scenario connections(String name, Supplier<FixtureConnectionsModel> model,
                                    BiConsumer<Stage, Integer> onFrame) {
            BiConsumer<Stage, Integer> onPage = (s, f) -> {
                s.pages().registry().select(PageId.CONNECTIONS);
                onFrame.accept(s, f);
            };
            return new Scenario(name, AppMode.ADVANCED, FixtureBoard::everyState, FixtureStoreModel::signedIn, model,
                    onPage);
        }
    }

    /** What a scenario's frame hook can reach. */
    private record Stage(FixtureBoard board, UserModeRenderer page, EventBusImpl bus, FixturePages.Built pages,
                         InspectorDock inspector, FixtureWindow window) {}

    private final Path outDir;
    private final List<Scenario> scenarios = scenarios();
    private int scenarioIndex;
    private int frame;
    private Controls ui;
    private Stage stage;
    private Shell shell;
    /** The mode the shell drew last; an inspector request can switch it, as in the app. */
    private AppMode mode;
    private int fbo;

    private ShellPreview(Path outDir) {
        this.outDir = outDir;
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "build/preview");
        Files.createDirectories(out);
        launch(new ShellPreview(out));
    }

    @Override
    protected void configure(Configuration config) {
        config.setTitle("Preview (dev only)");
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
        InspectorDock inspector = new InspectorDock(ui, new InspectorState(Clock.systemDefaultZone()), board);
        UserModeRenderer page = new UserModeRenderer(ui, inspector.state());
        EventBusImpl bus = new EventBusImpl();
        NotificationOverlay toasts = new NotificationOverlay(Clock.systemDefaultZone(),
                name -> board.clients().stream().filter(c -> c.pipe().filter(name::equals).isPresent())
                        .flatMap(c -> c.account().stream()).findFirst().orElse(name));
        toasts.subscribeTo(bus);
        FixturePages.Built pages = FixturePages.build(ui, page, board, s.store().get(), s.connections().get());
        FixtureWindow window = new FixtureWindow(new WindowRect(0, 0, WIDTH, HEIGHT));
        stage = new Stage(board, page, bus, pages, inspector, window);
        shell = new Shell(ui, pages.registry(), inspector, toasts,
                new FramelessChrome(ui, window, MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT));
        mode = s.mode();
        frame = 0;
    }

    @Override
    public void process() {
        ImGui.getIO().setMousePos(OFF_SCREEN, OFF_SCREEN);
        Scenario s = scenarios.get(scenarioIndex);
        s.onFrame().accept(stage, frame);
        mode = shell.render(mode, stage.board(), n -> { });
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

    private static final String WOODCUTTER = FixtureFleet.OAKHEART_PIPE;
    private static final ClientKey OAKHEART = ClientKey.account(FixtureFleet.OAKHEART_UUID);
    private static final ClientKey IDLE = ClientKey.account(FixtureFleet.QUILLON_UUID);
    private static final String WOODCUTTING = FixtureBoard.WOODCUTTING.name();

    private static List<Scenario> scenarios() {
        BiConsumer<Stage, Integer> nothing = (s, f) -> { };
        List<Scenario> scenarios = List.of(
                new Scenario("01-clients-7-every-state", FixtureBoard::everyState, nothing),
                new Scenario("02-clients-13", FixtureBoard::thirteenClients, nothing),
                new Scenario("03-clients-13-needs-attention", FixtureBoard::thirteenClients,
                        (s, f) -> PreviewSeams.showNeedsAttention(s.page())),
                new Scenario("03b-clients-13-running", FixtureBoard::thirteenClients,
                        (s, f) -> PreviewSeams.showRunning(s.page())),
                new Scenario("04-no-clients-yet", FixtureBoard::waiting, nothing),
                new Scenario("05a-restart-not-responding", () -> FixtureBoard.restart(RestartStep.NOT_RESPONDING),
                        nothing),
                new Scenario("05b-restart-closed-new-pipe-identifying",
                        () -> FixtureBoard.restart(RestartStep.CLOSED_AND_NEW_PIPE), nothing),
                new Scenario("05c-restart-resuming", () -> FixtureBoard.restart(RestartStep.RESUMING), nothing),
                new Scenario("05d-restart-running-again", () -> FixtureBoard.restart(RestartStep.RUNNING_AGAIN),
                        nothing),
                new Scenario("05e-dev-client-without-account", FixtureBoard::devClient, nothing),
                new Scenario("05f-clients-13-running-selected", FixtureBoard::thirteenClients, (s, f) -> {
                    if (f == 0) {
                        s.page().openInspector(s.board(), OAKHEART, WOODCUTTING, InspectorTab.SETTINGS);
                    }
                }),
                new Scenario("06-script-picker", FixtureBoard::everyState, (s, f) -> {
                    if (f == 0) {
                        s.page().openPicker(s.board(), IDLE);
                    }
                }),
                new Scenario("07-inspector-settings", FixtureBoard::everyState, ShellPreview::stagedEdits),
                new Scenario("08-inspector-script-ui", FixtureBoard::everyState, (s, f) -> {
                    if (f == 0) {
                        s.page().openInspector(s.board(), OAKHEART, WOODCUTTING, InspectorTab.SCRIPT_UI);
                    }
                }),
                new Scenario("09-toasts-failures", FixtureBoard::everyState, ShellPreview::failureToasts),
                new Scenario("10-toasts-reconnect", FixtureBoard::thirteenClients, ShellPreview::reconnectToasts),
                new Scenario("11-picker-subscriptions-installing", FixtureBoard::subscribed,
                        pickerOnRow(HERBLORE_INSTALLING_ROW)),
                new Scenario("12-picker-subscriptions-install-failed", FixtureBoard::subscribed,
                        pickerOnRow(RUNECRAFTING_FAILED_ROW)),
                new Scenario("13-picker-subscriptions-launcher-not-running",
                        () -> FixtureBoard.everyState().withSubscriptions(new SubscriptionGroup.Unavailable(
                                SubscriptionGroup.Reason.LAUNCHER_NOT_RUNNING, "")),
                        pickerOnRow(0)),
                new Scenario("14-picker-old-launcher-no-group",
                        () -> FixtureBoard.everyState().withSubscriptions(new SubscriptionGroup.Hidden()),
                        pickerOnRow(0)),
                new Scenario("15-picker-subscriptions-update-available", FixtureBoard::subscribedWithUpdate,
                        pickerOnRow(DIVINATION_UPDATE_ROW)),
                Scenario.advanced("20-advanced-opens-on-clients", FixtureBoard::everyState, nothing),
                Scenario.advanced("21-advanced-clients-inspector", FixtureBoard::everyState, (s, f) -> {
                    if (f == 0) {
                        s.page().openInspector(s.board(), OAKHEART, WOODCUTTING, InspectorTab.SETTINGS);
                    }
                }),
                Scenario.advanced("24-advanced-installed-selected", FixtureBoard::thirteenClients,
                        select(PageId.INSTALLED)),
                Scenario.advanced("25-advanced-groups-interim", FixtureBoard::waiting, select(PageId.GROUPS)),
                new Scenario("26-advanced-store-signed-out", AppMode.ADVANCED, FixtureBoard::everyState,
                        FixtureStoreModel::signedOut, select(PageId.STORE)),
                Scenario.advanced("27-advanced-settings-selected", FixtureBoard::waiting,
                        select(PageId.SETTINGS)),
                Scenario.advanced("28-advanced-inspector-client-settings-from-installed", FixtureBoard::everyState,
                        ShellPreview::clientSettingsFromInstalled),
                Scenario.advanced("29-advanced-inspector-client-script-ui", FixtureBoard::everyState,
                        fromPage(PageId.INSTALLED, new InspectorRequest(
                                new InspectorSubject.ClientScript(WOODCUTTER, WOODCUTTING), InspectorTab.SCRIPT_UI))),
                new Scenario("30-advanced-inspector-management-settings", FixtureBoard::everyState,
                        ShellPreview::managementSettingsFromNormal),
                Scenario.advanced("31-advanced-inspector-management-script-ui", FixtureBoard::everyState,
                        fromPage(PageId.MANAGEMENT, new InspectorRequest(
                                new InspectorSubject.ManagementScript(FixtureBoard.FLEET_MONITOR.name()),
                                InspectorTab.initialFor(false, true)))),
                Scenario.advanced("32-advanced-inspector-restore-defaults", FixtureBoard::everyState,
                        ShellPreview::managementOffDefaults),
                // The frameless window's own chrome: every scenario draws it, these two isolate it.
                new Scenario("40-window-frameless-normal", FixtureBoard::everyState, nothing),
                Scenario.advanced("41-window-frameless-advanced-maximised", FixtureBoard::everyState,
                        (s, f) -> s.window().maximise()));
        return Stream.of(scenarios, dashboardScenarios(), storeScenarios(), connectionsScenarios()).flatMap(List::stream).toList();
    }

    /** The Dashboard: busy, scoped by "View log", each dock tab, a filter, quiet, and an empty host. */
    private static List<Scenario> dashboardScenarios() {
        return List.of(
                Scenario.advanced("22-advanced-dashboard-busy", FixtureBoard::everyState,
                        select(PageId.DASHBOARD)),
                Scenario.advanced("23-advanced-view-log-opens-client-logs", FixtureBoard::everyState, (s, f) -> {
                    s.pages().registry().select(PageId.DASHBOARD);
                    if (f == 0) {
                        s.pages().dashboard().openLogs(Optional.of(TAMSIN_VALE));
                    }
                }),
                Scenario.advanced("33-advanced-dashboard-busy-attention", FixtureBoard::everyState,
                        dashboard(Fleet.BUSY, state -> {
                            state.setDockCollapsed(true);
                            state.toggleExpanded(CRASH_KEY);
                        }, SCROLL_BOTTOM)),
                Scenario.advanced("34-advanced-dashboard-logs-tab", FixtureBoard::everyState,
                        dashboard(Fleet.BUSY, state -> state.showTab(DockTab.LOGS), SCROLL_TOP)),
                Scenario.advanced("35-advanced-dashboard-events-tab", FixtureBoard::everyState,
                        dashboard(Fleet.BUSY, state -> state.showTab(DockTab.EVENTS), SCROLL_TOP)),
                Scenario.advanced("36-advanced-dashboard-problems-filter", FixtureBoard::everyState,
                        dashboard(Fleet.BUSY, state -> state.setFilter(RunnerFilter.PROBLEMS), SCROLL_TOP)),
                Scenario.advanced("37-advanced-dashboard-quiet", FixtureBoard::everyState,
                        dashboard(Fleet.QUIET, state -> state.setDockCollapsed(true), SCROLL_BOTTOM)),
                Scenario.advanced("38-advanced-dashboard-empty", FixtureBoard::waiting,
                        dashboard(Fleet.EMPTY, state -> { }, SCROLL_TOP)),
                Scenario.advanced("39-advanced-dashboard-live-model-empty-host", FixtureBoard::waiting, (s, f) -> {
                    s.pages().registry().select(PageId.DASHBOARD);
                    if (f == 0) {
                        s.pages().dashboardModel().useLive(s.pages().host(), s.board().actions());
                    }
                }));
    }

    /** The Script Store in every state it can be in, plus a catalogue big enough to scroll. */
    private static List<Scenario> storeScenarios() {
        BiConsumer<StorePage, Integer> nothing = (p, f) -> { };
        return List.of(
                Scenario.store("60-store-signed-in-detail", FixtureStoreModel::signedIn,
                        (p, f) -> StorePreviewSeams.showDetail(p, "woodcutting")),
                Scenario.store("61-store-ticked-install-bar", FixtureStoreModel::signedIn,
                        (p, f) -> onFirst(f, () -> StorePreviewSeams.tick(p, "woodcutting-fletcher", "restless-ghost"))),
                Scenario.store("62-store-loading", FixtureStoreModel::loading, nothing),
                Scenario.store("63-store-stale-launcher-not-answering", FixtureStoreModel::stale, nothing),
                Scenario.store("64-store-launcher-not-running", FixtureStoreModel::launcherNotRunning, nothing),
                Scenario.store("65-store-no-subscription", FixtureStoreModel::noSubscription, nothing),
                Scenario.store("66-store-catalogue-failed", FixtureStoreModel::failed, nothing),
                Scenario.store("67-store-no-favourites", FixtureStoreModel::noFavourites, nothing),
                Scenario.store("68-store-busy-catalogue", FixtureStoreModel::busy,
                        (p, f) -> onFirst(f, () -> StorePreviewSeams.tick(p, "s1", "s2", "s7"))),
                Scenario.store("69-store-nothing-matches", FixtureStoreModel::signedIn,
                        (p, f) -> StorePreviewSeams.showQuery(p,
                                StoreQuery.DEFAULT.withPrice(PriceFilter.PAID).withSearch("ghost"))),
                Scenario.store("70-store-filtered-free-questing", FixtureStoreModel::signedIn,
                        (p, f) -> StorePreviewSeams.showQuery(p, StoreQuery.DEFAULT.withPrice(PriceFilter.FREE)
                                .withCategoryToggled(ScriptCategory.QUESTING))),
                Scenario.store("71-store-delivery-disabled", FixtureStoreModel::deliveryDisabled, nothing),
                Scenario.store("72-store-installing", FixtureStoreModel::installing,
                        (p, f) -> StorePreviewSeams.showQuery(p, StoreQuery.DEFAULT.withTab(StoreTab.NOT_INSTALLED))),
                Scenario.store("73-store-install-failed", FixtureStoreModel::installFailed, nothing),
                Scenario.store("74-store-installed", FixtureStoreModel::installed, nothing),
                Scenario.store("75-store-refresh-failed", FixtureStoreModel::refreshFailed, nothing),
                Scenario.store("76-store-empty-catalogue", FixtureStoreModel::emptyCatalogue, nothing),
                Scenario.store("77-store-updates-view", FixtureStoreModel::signedIn, (p, f) -> {
                    StorePreviewSeams.showQuery(p, StoreQuery.DEFAULT.withTab(StoreTab.UPDATES));
                    StorePreviewSeams.showDetail(p, "woodcutting");
                }),
                Scenario.store("78-store-older-agent-detail", FixtureStoreModel::signedIn,
                        (p, f) -> StorePreviewSeams.showDetail(p, "location-probe")),
                Scenario.store("79-store-category-list", FixtureStoreModel::busy, (p, f) -> {
                    if (f == 0) {
                        StorePreviewSeams.showQuery(p, StoreQuery.DEFAULT.withCategoryToggled(ScriptCategory.MINING)
                                .withCategoryToggled(ScriptCategory.FISHING));
                    } else if (f == 1) {
                        StorePreviewSeams.openCategoryList(p);
                    }
                }));
    }

    private static final String TAMSIN_VALE = "BotWithUs_15002";
    private static final String CRASH_KEY = "crash:" + TAMSIN_VALE + ":Cook's Assistant";
    private static final float SCROLL_TOP = 0f;
    private static final float SCROLL_BOTTOM = 1f;

    /** The Dashboard pretending to be {@code fleet}, with {@code setup} applied and the body held scrolled. */
    private static BiConsumer<Stage, Integer> dashboard(Fleet fleet, Consumer<DashboardState> setup, float scroll) {
        return (s, f) -> {
            s.pages().registry().select(PageId.DASHBOARD);
            if (f == 0) {
                s.pages().dashboardModel().fixture().show(fleet);
                setup.accept(s.pages().dashboard().state());
            }
            DashboardPreviewSeams.holdScroll(s.pages().dashboard(), scroll);
        };
    }

    private static void onFirst(int frame, Runnable action) {
        if (frame == 0) {
            action.run();
        }
    }

    /** The Connections page: busy, each detail pane, scanning, no pipes, auto-connect off, and a filter. */
    private static List<Scenario> connectionsScenarios() {
        BiConsumer<Stage, Integer> nothing = (s, f) -> { };
        return List.of(
                Scenario.connections("80-connections-busy", FixtureConnectionsModel::busy, nothing),
                Scenario.connections("81-connections-reconnecting-detail", FixtureConnectionsModel::busy,
                        selectConnection(FixtureConnectionsModel.HOLLOWMERE)),
                Scenario.connections("82-connections-closed-detail", FixtureConnectionsModel::busy,
                        selectConnection(FixtureConnectionsModel.BRACKENRIDGE)),
                Scenario.connections("83-connections-live-detail-output-filtered",
                        () -> FixtureConnectionsModel.busy().filteredToFernmoss(),
                        selectConnection(FixtureConnectionsModel.OAKHEART)),
                Scenario.connections("84-connections-scanning", FixtureConnectionsModel::scanningEmpty, nothing),
                Scenario.connections("85-connections-no-pipes", FixtureConnectionsModel::noPipes, nothing),
                Scenario.connections("86-connections-auto-connect-off", FixtureConnectionsModel::autoConnectOff,
                        selectConnection(FixtureConnectionsModel.QUILL_PIPE)),
                Scenario.connections("87-connections-problems-output-filtered",
                        () -> FixtureConnectionsModel.busy().filteredToFernmoss(),
                        (s, f) -> ConnectionsPreviewSeams.showFilter(s.pages().connections(), RowFilter.PROBLEMS)));
    }

    /** Selects the Connections row with {@code id}, opening its detail pane. */
    private static BiConsumer<Stage, Integer> selectConnection(String id) {
        return (s, f) -> {
            if (f == 0) {
                ConnectionsPreviewSeams.select(s.pages().connections(), id);
            }
        };
    }

    /** A short management form with one field off its default, so "Restore defaults" is live. */
    private static void managementOffDefaults(Stage s, int f) {
        fromPage(PageId.MANAGEMENT, new InspectorRequest(
                new InspectorSubject.ManagementScript(FixtureBoard.LOGIN_WATCHER.name()),
                InspectorTab.SETTINGS)).accept(s, f);
        if (f == 3) {
            InspectorPreviewSeams.stageEdit(s.inspector(), "delay", 45);
        }
    }

    /**
     * Starts on {@code page} and asks for the inspector as a "Settings" button
     * there would; the shell routes to the page that owns the script.
     */
    private static BiConsumer<Stage, Integer> fromPage(PageId page, InspectorRequest request) {
        return (s, f) -> {
            if (f == 0) {
                s.pages().registry().select(page);
            } else if (f == 1) {
                s.inspector().state().request(request);
            }
        };
    }

    /** Installed scripts' Settings on Oakheart's Woodcutting: lands on Clients, two fields edited. */
    private static void clientSettingsFromInstalled(Stage s, int f) {
        fromPage(PageId.INSTALLED, new InspectorRequest(
                new InspectorSubject.ClientScript(WOODCUTTER, WOODCUTTING), InspectorTab.SETTINGS)).accept(s, f);
        if (f == 3) {
            InspectorPreviewSeams.stageEdit(s.inspector(), "stopValue", 95);
            InspectorPreviewSeams.stageEdit(s.inspector(), "disposal", 2);
        }
    }

    /**
     * A management script's Settings asked for from Normal mode: the shell
     * switches to Advanced and Management. One field of every type, two edited.
     */
    private static void managementSettingsFromNormal(Stage s, int f) {
        if (f == 1) {
            s.inspector().state().request(new InspectorRequest(
                    new InspectorSubject.ManagementScript(FixtureBoard.BREAK_SCHEDULER.name()),
                    InspectorTab.SETTINGS));
        } else if (f == 3) {
            InspectorPreviewSeams.stageEdit(s.inspector(), "logOut", false);
            InspectorPreviewSeams.stageEdit(s.inspector(), "quietHours", "22:00-06:00");
        }
    }

    private static BiConsumer<Stage, Integer> select(PageId id) {
        return (s, f) -> s.pages().registry().select(id);
    }

    /** Row indices in {@link FixtureBoard#subscribed()}'s picker; subscriptions are listed by name, first. */
    private static final int DIVINATION_UPDATE_ROW = 1;
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
            s.page().openInspector(s.board(), OAKHEART, WOODCUTTING, InspectorTab.SETTINGS);
        } else if (f == 2) {
            InspectorPreviewSeams.stageEdit(s.inspector(), "stopValue", 95);
            InspectorPreviewSeams.stageEdit(s.inspector(), "disposal", 2);
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
        s.bus().publish(new ReconnectStateChangedEvent(FixtureFleet.HOLLOWMERE_PIPE,
                new ReconnectState.GivingUp(0L, 5, new IllegalStateException("pipe gone"))));
        s.bus().publish(new ReconnectStateChangedEvent(FixtureFleet.HOLLOWMERE_PIPE,
                new ReconnectState.Connected(0L)));
        s.bus().publish(new ReconnectStateChangedEvent(FixtureFleet.HOLLOWMERE_PIPE,
                new ReconnectState.Reconnecting(0L, 2, 4000L)));
    }
}
