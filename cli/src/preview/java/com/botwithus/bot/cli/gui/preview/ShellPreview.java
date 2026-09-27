package com.botwithus.bot.cli.gui.preview;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.cli.events.HostEvent.ClientResumed;
import com.botwithus.bot.cli.events.HostEvent.ConnectionLost;
import com.botwithus.bot.cli.events.HostEvent.ReconnectStateChanged;
import com.botwithus.bot.cli.events.HostEvent.ScriptCrashed;
import com.botwithus.bot.cli.events.HostEvent.ScriptLoadFailed;
import com.botwithus.bot.cli.events.HostEvent.ScriptStalled;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.gui.AppMode;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.FontLoader;
import com.botwithus.bot.cli.gui.FramelessChrome;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.PreviewFonts;
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
import com.botwithus.bot.cli.gui.pages.groups.BusyChoice;
import com.botwithus.bot.cli.gui.pages.groups.GroupsPage;
import com.botwithus.bot.cli.gui.pages.groups.GroupsPreviewSeams;
import com.botwithus.bot.cli.gui.pages.installed.InstalledPage;
import com.botwithus.bot.cli.gui.pages.installed.InstalledPreviewSeams;
import com.botwithus.bot.cli.gui.preview.FixtureDashboardModel.Fleet;
import com.botwithus.bot.cli.gui.notify.NotificationOverlay;
import com.botwithus.bot.cli.gui.notify.ToastFeed;
import com.botwithus.bot.cli.gui.pages.settings.SettingsAction;
import com.botwithus.bot.cli.gui.pages.settings.SettingsPreviewSeams;
import com.botwithus.bot.cli.gui.pages.settings.SettingsSection;
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
import com.botwithus.bot.cli.management.Target;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SaveStatus;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.cli.settings.TextSize;
import com.botwithus.bot.core.pipe.PipeException;

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
import java.util.concurrent.CancellationException;
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
    private record Stage(FixtureBoard board, UserModeRenderer page, Consumer<HostEvent> toasts,
                         FixtureToasts fleet, FixturePages.Built pages,
                         InspectorDock inspector, FixtureWindow window, Consumer<TextSize> textSize) {}

    private final Path outDir;
    private final List<Scenario> scenarios = scenarios();
    /** Defaults only, read by the toast feed; kept under the output folder, never the user's home. */
    private HostSettings toastSettings;
    private int scenarioIndex;
    private int frame;
    private Controls ui;
    private Stage stage;
    private Shell shell;
    /** The mode the shell drew last; an inspector request can switch it, as in the app. */
    private AppMode mode;
    private int fbo;
    /** The text size the next scenario frame should use, and the one the atlas holds now. */
    private TextSize textSize = TextSize.PERCENT_100;
    private TextSize shownTextSize = TextSize.PERCENT_100;

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
        toastSettings = HostSettings.open(outDir.resolve("toast-settings"));
        startScenario();
    }

    private void startScenario() {
        Scenario s = scenarios.get(scenarioIndex);
        FixtureBoard board = s.board().get();
        InspectorDock inspector = new InspectorDock(ui, new InspectorState(Clock.systemDefaultZone()), board);
        UserModeRenderer page = new UserModeRenderer(ui, inspector.state());
        NotificationOverlay toasts = new NotificationOverlay(Clock.systemDefaultZone());
        FixtureToasts fleet = new FixtureToasts(board);
        ToastFeed feed = new ToastFeed(toasts, toastSettings, fleet);
        FixturePages.Built pages = FixturePages.build(ui, page, board, s.store().get(), s.connections().get());
        FixtureWindow window = new FixtureWindow(new WindowRect(0, 0, WIDTH, HEIGHT));
        stage = new Stage(board, page, feed, fleet, pages, inspector, window, size -> textSize = size);
        shell = new Shell(ui, pages.registry(), inspector, toasts,
                new FramelessChrome(ui, window, MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT));
        mode = s.mode();
        frame = 0;
        textSize = TextSize.PERCENT_100;
    }

    /** Applies a scenario's text size between frames, as the app does when the setting changes. */
    @Override
    protected void startFrame() {
        if (textSize != shownTextSize) {
            PreviewFonts.rebuild(ui, imGuiGl3, textSize, ADVANCED_FONT_PX);
            shownTextSize = textSize;
        }
        super.startFrame();
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
        return Stream.of(scenarios, dashboardScenarios(), storeScenarios(), connectionsScenarios(),
                installedScenarios(), settingsScenarios(), groupsScenarios(), toastScenarios(),
                managementSettingsScenarios())
                .flatMap(List::stream).toList();
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

    /**
     * Groups: the busy page, an empty group, no groups, the Start script plan, the
     * Add clients and New group dialogs, unresolved members with a refusal notice
     * and ticked rows, the Stop all confirm, and a rename.
     */
    private static List<Scenario> groupsScenarios() {
        GroupId woodcutters = FixtureGroupsModel.WOODCUTTERS;
        return List.of(
                groups("100-groups-busy", (s, page) -> GroupsPreviewSeams.select(page, woodcutters)),
                groups("101-groups-empty-group", (s, page) -> {
                    s.pages().groupsModel().showEmptyGroup();
                    GroupsPreviewSeams.select(page, FixtureGroupsModel.YEW_TEAM);
                }),
                groups("102-groups-none", (s, page) -> s.pages().groupsModel().showNoGroups()),
                Scenario.advanced("103-groups-start-plan", FixtureBoard::everyState, ShellPreview::groupsStartPlan),
                Scenario.advanced("104-groups-add-clients", FixtureBoard::everyState, ShellPreview::groupsAddClients),
                Scenario.advanced("105-groups-new-group", FixtureBoard::everyState, ShellPreview::groupsNewGroup),
                groups("106-groups-unresolved-members", (s, page) -> {
                    s.pages().groupsModel().showUnresolved();
                    GroupsPreviewSeams.select(page, FixtureGroupsModel.OLD_GROUP);
                    GroupsPreviewSeams.tick(page, FixtureGroupsModel.OLD_GROUP, "pipe:BotWithUs_9120");
                }),
                groups("107-groups-stop-all-confirm", (s, page) -> {
                    GroupsPreviewSeams.select(page, woodcutters);
                    GroupsPreviewSeams.askStopAll(page);
                }),
                groups("108-groups-rename", (s, page) -> {
                    GroupsPreviewSeams.select(page, FixtureGroupsModel.QUESTERS);
                    GroupsPreviewSeams.startRename(page, "Questers");
                }));
    }

    private static Scenario groups(String name, BiConsumer<Stage, GroupsPage> setUp) {
        return Scenario.advanced(name, FixtureBoard::everyState, groups(setUp));
    }

    /** Opens Groups and, on the first frame, puts it in a state through its seams. */
    private static BiConsumer<Stage, Integer> groups(BiConsumer<Stage, GroupsPage> setUp) {
        return (s, f) -> {
            if (f == 0) {
                s.pages().registry().select(PageId.GROUPS);
                setUp.accept(s, s.pages().groups());
            }
        };
    }

    /** Start script on Woodcutters with Divination picked: every kind of member shows in the plan. */
    private static void groupsStartPlan(Stage s, int f) {
        groups((stage, page) -> GroupsPreviewSeams.openStartScript(page, FixtureGroupsModel.WOODCUTTERS))
                .accept(s, f);
        if (f == 2) {
            GroupsPreviewSeams.choose(s.pages().groups(), DIVINATION_CATALOG_ROW, BusyChoice.SWITCH);
        }
    }

    /** Add clients to Questers with two clients ticked; the dev client is listed but cannot join. */
    private static void groupsAddClients(Stage s, int f) {
        groups((stage, page) -> GroupsPreviewSeams.openAddClients(page, FixtureGroupsModel.QUESTERS))
                .accept(s, f);
        if (f == 2) {
            GroupsPreviewSeams.fill(s.pages().groups(), "", List.of(
                    ClientKey.account(FixtureGroupsModel.KESTREL), ClientKey.account(FixtureGroupsModel.SABLETON)));
        }
    }

    /** New group, named, with one client ticked. */
    private static void groupsNewGroup(Stage s, int f) {
        // Filled on the frame it opens: once the name field has the keyboard, ImGui keeps its own copy of the text.
        groups((stage, page) -> {
            GroupsPreviewSeams.openNewGroup(page);
            GroupsPreviewSeams.fill(page, "Yew team", List.of(ClientKey.account(FixtureGroupsModel.KESTREL),
                    ClientKey.account(FixtureGroupsModel.SABLETON)));
        }).accept(s, f);
    }

    /** Installed scripts: busy, empty, failures, both detail tabs, Start on… and an armed stop. */
    private static List<Scenario> installedScenarios() {
        String woodcutting = FixtureInstalledModel.WOODCUTTING;
        return List.of(
                installed("90-installed-busy", (s, page) -> InstalledPreviewSeams.selectClientsTab(page, woodcutting)),
                installed("91-installed-empty-folder", (s, page) -> s.pages().installedModel().showEmpty()),
                installed("92-installed-failures", (s, page) -> {
                    s.pages().installedModel().showFailures();
                    InstalledPreviewSeams.showProblemsOnly(page);
                    InstalledPreviewSeams.openTrace(page, FixtureInstalledModel.FAILED_JAR);
                }),
                installed("93-installed-detail-clients", (s, page) ->
                        InstalledPreviewSeams.selectClientsTab(page, FixtureInstalledModel.DIVINATION)),
                installed("94-installed-detail-about-update", (s, page) ->
                        InstalledPreviewSeams.selectAboutTab(page, woodcutting)),
                installed("95-installed-detail-about-local-build", (s, page) ->
                        InstalledPreviewSeams.selectAboutTab(page, FixtureInstalledModel.EXAMPLE)),
                Scenario.advanced("96-installed-start-on-dialog", FixtureBoard::everyState,
                        ShellPreview::installedStartOn),
                installed("97-installed-stop-everywhere-confirm", (s, page) ->
                        InstalledPreviewSeams.armStop(page, woodcutting)));
    }

    private static Scenario installed(String name, BiConsumer<Stage, InstalledPage> setUp) {
        return Scenario.advanced(name, FixtureBoard::everyState, installed(setUp));
    }

    /** Opens Installed scripts and, on the first frame, puts it in a state through its seams. */
    private static BiConsumer<Stage, Integer> installed(BiConsumer<Stage, InstalledPage> setUp) {
        return (s, f) -> {
            if (f == 0) {
                s.pages().registry().select(PageId.INSTALLED);
                setUp.accept(s, s.pages().installed());
            }
        };
    }

    /** Start on… for Woodcutting with two clients ticked. */
    private static void installedStartOn(Stage s, int f) {
        installed((stage, page) -> InstalledPreviewSeams.openStartOn(page, FixtureInstalledModel.WOODCUTTING))
                .accept(s, f);
        if (f == 2) {
            InstalledPreviewSeams.tick(s.pages().installed(), "BotWithUs_4468");
            InstalledPreviewSeams.tick(s.pages().installed(), "BotWithUs_8936");
        }
    }

    /** Settings: each section, the find box, refused values, the save line, and a larger text size. */
    private static List<Scenario> settingsScenarios() {
        return List.of(
                Scenario.advanced("42-settings-reconnect-preview", FixtureBoard::everyState,
                        settingsAt(SettingsSection.RECONNECTING)),
                Scenario.advanced("43-settings-accounts", FixtureBoard::everyState,
                        settingsAt(SettingsSection.ACCOUNTS)),
                Scenario.advanced("44-settings-scripts-notifications", FixtureBoard::everyState,
                        settingsAt(SettingsSection.SCRIPTS)),
                Scenario.advanced("45-settings-interface-diagnostics", FixtureBoard::everyState,
                        settingsAt(SettingsSection.INTERFACE)),
                Scenario.advanced("46-settings-all-config-keys", FixtureBoard::everyState,
                        settingsAt(SettingsSection.ALL_KEYS)),
                Scenario.advanced("47-settings-find-timeout", FixtureBoard::everyState, settingsFind("timeout")),
                Scenario.advanced("48-settings-find-nothing", FixtureBoard::everyState, settingsFind("zzz")),
                Scenario.advanced("49-settings-invalid-number", FixtureBoard::everyState,
                        ShellPreview::settingsInvalidNumber),
                Scenario.advanced("52-settings-invalid-stall-threshold", FixtureBoard::everyState,
                        ShellPreview::settingsInvalidStall),
                Scenario.advanced("50-settings-saving-after-export", FixtureBoard::everyState,
                        ShellPreview::settingsSaving),
                Scenario.advanced("51-settings-save-failed", FixtureBoard::everyState,
                        ShellPreview::settingsSaveFailed),
                Scenario.advanced("53-settings-text-size-125", FixtureBoard::everyState, (s, f) -> {
                    s.pages().registry().select(PageId.SETTINGS);
                    if (f == 0) {
                        s.textSize().accept(TextSize.PERCENT_125);
                    } else if (f == 2) {
                        SettingsPreviewSeams.showSection(s.pages().settings(), SettingsSection.INTERFACE);
                    }
                }));
    }

    /** Opens Settings and scrolls to {@code section} once the page has laid out, as the section list does. */
    private static BiConsumer<Stage, Integer> settingsAt(SettingsSection section) {
        return (s, f) -> {
            s.pages().registry().select(PageId.SETTINGS);
            if (f == 2) {
                SettingsPreviewSeams.showSection(s.pages().settings(), section);
            }
        };
    }

    private static BiConsumer<Stage, Integer> settingsFind(String query) {
        return (s, f) -> {
            s.pages().registry().select(PageId.SETTINGS);
            if (f == 0) {
                SettingsPreviewSeams.find(s.pages().settings(), query);
            }
        };
    }

    /** Words typed into a number box: refused, with the rule under the row. */
    private static void settingsInvalidNumber(Stage s, int f) {
        s.pages().registry().select(PageId.SETTINGS);
        if (f == 1) {
            SettingsPreviewSeams.type(s.pages().settings(), SettingKeys.RPC_TIMEOUT_MS.name(), "ten seconds");
        }
    }

    /** A stall threshold under its minimum: refused in seconds, the unit the row shows. */
    private static void settingsInvalidStall(Stage s, int f) {
        s.pages().registry().select(PageId.SETTINGS);
        if (f == 2) {
            SettingsPreviewSeams.showSection(s.pages().settings(), SettingsSection.SCRIPTS);
            SettingsPreviewSeams.type(s.pages().settings(), SettingKeys.STALL_AFTER_MS.name(), "5");
        }
    }

    private static void settingsSaving(Stage s, int f) {
        s.pages().registry().select(PageId.SETTINGS);
        if (f == 0) {
            s.pages().settingsModel().showStatus(new SaveStatus.Saving());
            s.pages().settingsModel().run(SettingsAction.EXPORT_SETTINGS);
        } else if (f == 2) {
            SettingsPreviewSeams.showSection(s.pages().settings(), SettingsSection.ABOUT);
        }
    }

    private static void settingsSaveFailed(Stage s, int f) {
        s.pages().registry().select(PageId.SETTINGS);
        if (f == 0) {
            s.pages().settingsModel().showStatus(new SaveStatus.Failed(
                    "Could not save settings: config.properties is in use by another program", Instant.now()));
        }
    }

    /**
     * The inspector's "Settings for" picker on Break Scheduler, which manages
     * Woodcutters, Fernmoss's Divination and Kestrel Moor's Walk to Flag.
     */
    private static List<Scenario> managementSettingsScenarios() {
        Consumer<InspectorDock> noEdits = dock -> { };
        return List.of(
                Scenario.advanced("120-management-settings-for-defaults", FixtureBoard::everyState,
                        settingsFor(Optional.empty(), noEdits)),
                Scenario.advanced("121-management-settings-for-group", FixtureBoard::everyState,
                        settingsFor(Optional.of(FixtureManagementSettings.WOODCUTTERS), noEdits)),
                Scenario.advanced("122-management-settings-for-client-script-in-group", FixtureBoard::everyState,
                        settingsFor(Optional.of(FixtureManagementSettings.FERNMOSS_DIVINATION), noEdits)),
                Scenario.advanced("123-management-settings-for-new-own-value", FixtureBoard::everyState,
                        settingsFor(Optional.of(FixtureManagementSettings.KESTREL_WALK),
                                dock -> InspectorPreviewSeams.stageEdit(dock, "breakLength", 30))),
                Scenario.advanced("124-management-settings-for-back-to-inherited", FixtureBoard::everyState,
                        settingsFor(Optional.of(FixtureManagementSettings.WOODCUTTERS),
                                dock -> InspectorPreviewSeams.stageEdit(dock, "breakEvery", 90))));
    }

    /** Opens Break Scheduler's settings for {@code target} from Management, then makes {@code edits}. */
    private static BiConsumer<Stage, Integer> settingsFor(Optional<Target> target, Consumer<InspectorDock> edits) {
        BiConsumer<Stage, Integer> open = fromPage(PageId.MANAGEMENT, new InspectorRequest(
                new InspectorSubject.ManagementScript(FixtureBoard.BREAK_SCHEDULER.name(), target),
                InspectorTab.SETTINGS));
        return (s, f) -> {
            open.accept(s, f);
            if (f == STAGE_EDITS_FRAME) {
                edits.accept(s.inspector());
            }
        };
    }

    /** The frame a staged edit lands on: after the request has routed and the form has drawn once. */
    private static final int STAGE_EDITS_FRAME = 3;

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
    /** Divination's row in the Groups Start script dialog, which lists {@link FixtureBoard}'s catalogue in order. */
    private static final int DIVINATION_CATALOG_ROW = 2;

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

    // ── Toasts ─────────────────────────────────────────────────────────────

    private static final String HOLLOWMERE = "Hollowmere";
    private static final String OAKHEART_NAME = "Oakheart";
    private static final String TAMSIN_VALE_NAME = "Tamsin Vale";
    private static final int GAVE_UP_ATTEMPTS = 5;
    private static final long FIRST_RETRY_MS = 500L;
    private static final long LATER_RETRY_MS = 4000L;
    /** When the stop lands in "stop retrying": after the retry toast has slid in. */
    private static final int STOP_FRAME = 10;

    /** Each toast kind, fed through the real toast feed as host events. */
    private static List<Scenario> toastScenarios() {
        return List.of(
                new Scenario("110-toast-not-responding", FixtureBoard::everyState, once((s, at) -> {
                    s.toasts().accept(new ConnectionLost(s.fleet().ref(HOLLOWMERE), new PipeException("eof"), at));
                    s.toasts().accept(reconnect(s, HOLLOWMERE,
                            new ReconnectState.Reconnecting(0L, 1, FIRST_RETRY_MS)));
                })),
                new Scenario("111-toast-gave-up-try-again", FixtureBoard::everyState, once((s, at) ->
                        s.toasts().accept(reconnect(s, HOLLOWMERE, new ReconnectState.GivingUp(0L,
                                GAVE_UP_ATTEMPTS, new PipeException("pipe did not come back")))))),
                new Scenario("112-toast-client-closed", FixtureBoard::everyState, once((s, at) ->
                        s.toasts().accept(reconnect(s, "Brackenridge", new ReconnectState.GivingUp(0L, 1,
                                new PipeException("process exited")))))),
                new Scenario("113-toast-client-back-new-pipe", FixtureBoard::everyState, once((s, at) ->
                        s.toasts().accept(new ClientResumed(s.fleet().ref(OAKHEART_NAME),
                                Optional.of("BotWithUs_19230"), at)))),
                new Scenario("114-toast-reconnected", FixtureBoard::everyState, once((s, at) ->
                        s.toasts().accept(reconnect(s, OAKHEART_NAME, new ReconnectState.Connected(0L))))),
                new Scenario("115-toast-script-stalled", FixtureBoard::everyState, once((s, at) ->
                        s.toasts().accept(new ScriptStalled(s.fleet().ref("Fernmoss"), "Divination", at)))),
                new Scenario("116-toast-script-crashed", FixtureBoard::everyState, once(ShellPreview::crash)),
                new Scenario("117-toast-load-failed-no-clients", FixtureBoard::waiting,
                        once(ShellPreview::loadFailed)),
                new Scenario("118-toast-stop-retrying-shows-none", FixtureBoard::everyState,
                        ShellPreview::stopRetrying),
                new Scenario("119-toasts-stack-of-three", FixtureBoard::everyState, once((s, at) -> {
                    s.toasts().accept(new ScriptStalled(s.fleet().ref("Fernmoss"), "Divination", at));
                    crash(s, at);
                    loadFailed(s, at);
                    s.toasts().accept(reconnect(s, OAKHEART_NAME, new ReconnectState.Connected(0L)));
                })));
    }

    /** Runs {@code post} on the first frame only, with the time it happened. */
    private static BiConsumer<Stage, Integer> once(BiConsumer<Stage, Instant> post) {
        return (s, f) -> {
            if (f == 0) {
                post.accept(s, Instant.now());
            }
        };
    }

    private static ReconnectStateChanged reconnect(Stage s, String account, ReconnectState state) {
        return new ReconnectStateChanged(s.fleet().ref(account), state, Instant.now());
    }

    private static void crash(Stage s, Instant at) {
        s.toasts().accept(new ScriptCrashed(s.fleet().ref(TAMSIN_VALE_NAME), "Cook's Assistant",
                new LastCrash(Phase.ON_LOOP, 0L, at, new NullPointerException()), at));
    }

    private static void loadFailed(Stage s, Instant at) {
        s.toasts().accept(new ScriptLoadFailed(Path.of("scripts", "woodcutting-1.0-SNAPSHOT.jar"),
                new IllegalStateException("missing module-info provides"), at));
    }

    /** A retry toast, then the user stops retrying: it slides out and no "gave up" error follows. */
    private static void stopRetrying(Stage s, int f) {
        if (f == 0) {
            s.toasts().accept(reconnect(s, HOLLOWMERE, new ReconnectState.Reconnecting(0L, 1, FIRST_RETRY_MS)));
        } else if (f == STOP_FRAME) {
            s.toasts().accept(reconnect(s, HOLLOWMERE, new ReconnectState.GivingUp(0L, 1,
                    new CancellationException("Stopped retrying on request"))));
        }
    }

    private static void failureToasts(Stage s, int f) {
        once((stage, at) -> {
            loadFailed(stage, at);
            crash(stage, at);
            stage.toasts().accept(new ConnectionLost(stage.fleet().ref(HOLLOWMERE), null, at));
        }).accept(s, f);
    }

    private static void reconnectToasts(Stage s, int f) {
        once((stage, at) -> {
            stage.toasts().accept(reconnect(stage, "Quillon", new ReconnectState.GivingUp(0L, GAVE_UP_ATTEMPTS,
                    new PipeException("pipe did not come back"))));
            stage.toasts().accept(reconnect(stage, OAKHEART_NAME, new ReconnectState.Connected(0L)));
            stage.toasts().accept(reconnect(stage, HOLLOWMERE,
                    new ReconnectState.Reconnecting(0L, 1, LATER_RETRY_MS)));
        }).accept(s, f);
    }
}
