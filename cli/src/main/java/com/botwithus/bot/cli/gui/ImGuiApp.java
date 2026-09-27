package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.AutoStartManager;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.clients.ClientRecord;
import com.botwithus.bot.cli.command.CommandRegistry;
import com.botwithus.bot.cli.command.impl.ActionsCommand;
import com.botwithus.bot.cli.command.impl.AutoStartCommand;
import com.botwithus.bot.cli.command.impl.ClearCommand;
import com.botwithus.bot.cli.command.impl.ClientCommand;
import com.botwithus.bot.cli.command.impl.ConfigCommand;
import com.botwithus.bot.cli.command.impl.ConnectCommand;
import com.botwithus.bot.cli.command.impl.EventsCommand;
import com.botwithus.bot.cli.command.impl.ExitCommand;
import com.botwithus.bot.cli.command.impl.GroupCommand;
import com.botwithus.bot.cli.command.impl.HelpCommand;
import com.botwithus.bot.cli.command.impl.LogsCommand;
import com.botwithus.bot.cli.command.impl.ManagementScriptsCommand;
import com.botwithus.bot.cli.command.impl.MetricsCommand;
import com.botwithus.bot.cli.command.impl.MountCommand;
import com.botwithus.bot.cli.command.impl.PingCommand;
import com.botwithus.bot.cli.command.impl.PlayerCommand;
import com.botwithus.bot.cli.command.impl.ProfileCommand;
import com.botwithus.bot.cli.command.impl.ReloadCommand;
import com.botwithus.bot.cli.command.impl.ScreenshotCommand;
import com.botwithus.bot.cli.command.impl.ScriptsCommand;
import com.botwithus.bot.cli.command.impl.StreamCommand;
import com.botwithus.bot.cli.command.impl.UnmountCommand;
import com.botwithus.bot.cli.diag.MetricsCollection;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.gui.inspector.InspectorDock;
import com.botwithus.bot.cli.gui.inspector.InspectorState;
import com.botwithus.bot.cli.gui.inspector.LiveInspectorSource;
import com.botwithus.bot.cli.gui.nav.Page;
import com.botwithus.bot.cli.gui.nav.PageId;
import com.botwithus.bot.cli.gui.nav.PageRegistry;
import com.botwithus.bot.cli.gui.nav.SecondLine;
import com.botwithus.bot.cli.gui.notify.HostToasts;
import com.botwithus.bot.cli.gui.notify.NotificationOverlay;
import com.botwithus.bot.cli.gui.notify.ToastRoutes;
import com.botwithus.bot.cli.gui.pages.ClientsPage;
import com.botwithus.bot.cli.gui.pages.connections.ConnectCommandPipes;
import com.botwithus.bot.cli.gui.pages.connections.ConnectionsPage;
import com.botwithus.bot.cli.gui.pages.connections.LiveConnectionsModel;
import com.botwithus.bot.cli.gui.pages.groups.GroupsPage;
import com.botwithus.bot.cli.gui.pages.groups.LiveGroupsModel;
import com.botwithus.bot.cli.gui.pages.dashboard.CommandConsole;
import com.botwithus.bot.cli.gui.pages.dashboard.DashboardPage;
import com.botwithus.bot.cli.gui.pages.dashboard.LiveDashboardModel;
import com.botwithus.bot.cli.gui.pages.installed.InstalledPage;
import com.botwithus.bot.cli.gui.pages.installed.LiveInstalledModel;
import com.botwithus.bot.cli.gui.pages.management.LiveManagementModel;
import com.botwithus.bot.cli.gui.pages.management.ManagementPage;
import com.botwithus.bot.cli.gui.pages.settings.LiveSettingsModel;
import com.botwithus.bot.cli.gui.pages.settings.SettingsPage;
import com.botwithus.bot.cli.gui.pages.store.LiveStoreModel;
import com.botwithus.bot.cli.gui.pages.store.StoreCatalogue;
import com.botwithus.bot.cli.gui.pages.store.StorePage;
import com.botwithus.bot.cli.gui.usermode.UserModeRenderer;
import com.botwithus.bot.cli.gui.usermode.board.ClientBoard;
import com.botwithus.bot.cli.gui.usermode.board.LiveClientBoard;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogBufferAppender;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.cli.output.AnsiCodes;
import com.botwithus.bot.cli.sdn.FavouritesStore;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.cli.stream.StreamManager;
import com.botwithus.bot.core.config.ScriptProfileStore;
import com.botwithus.bot.core.sdn.SdnCatalogueRefresher;
import com.botwithus.bot.core.sdn.SdnCatalogueSource;
import com.botwithus.bot.core.sdn.SdnInstaller;
import com.botwithus.bot.core.runtime.LocalScriptLoader;
import com.botwithus.bot.core.runtime.ManagementScriptLoader;

import imgui.ImGui;
import imgui.app.Application;
import imgui.app.Configuration;
import imgui.flag.ImGuiConfigFlags;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Main imgui-based application. The shell draws Normal mode's Clients page, or
 * Advanced mode's sidebar and the selected page from the {@link PageRegistry},
 * with the one config inspector docked beside whichever page owns its script.
 */
public class ImGuiApp extends Application {

    public ImGuiApp() {}

    private static final Logger log = LoggerFactory.getLogger(ImGuiApp.class);

    private static final float UI_FONT_BASE_PX = 17f;
    /** The taskbar's name for the window. It names no connection: no one client owns the app. */
    private static final String WINDOW_TITLE = "BotWithUs";
    /** How long the Installed scripts page reuses one read of the host: its badge and body share it. */
    private static final Duration INSTALLED_VIEW_MAX_AGE = Duration.ofMillis(250);
    /** How long the Management page reuses one read of the host: its badge and body share it. */
    private static final Duration MANAGEMENT_VIEW_MAX_AGE = Duration.ofMillis(250);

    // The ASCII-art \\ sequences javac reads as line-continuation markers; suppression
    // is narrower than rewriting the banner as concatenated string literals.
    @SuppressWarnings("text-blocks")
    private static final String BANNER = """

            ____        _ __        ___ _   _     _   _
           | __ )  ___ | |\\ \\      / (_) |_| |__ | | | |___
           |  _ \\ / _ \\| __\\ \\ /\\ / /| | __| '_ \\| | | / __|
           | |_) | (_) | |_ \\ V  V / | | |_| | | | |_| \\__ \\
           |____/ \\___/ \\__| \\_/\\_/  |_|\\__|_| |_|\\___/|___/
                        Script Manager

              Type 'help' for available commands.
            """;

    private TextureManager textureManager;
    private AnsiOutputBuffer outputBuffer;
    private CliContext ctx;
    private CommandRegistry registry;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bwu-cmd");
        t.setDaemon(true);
        return t;
    });

    // Pages. The Dashboard is kept so "View log" can bring its Logs tab forward, and
    // Management so a robot link elsewhere can open it on one script.
    private PageRegistry pages;
    private DashboardPage dashboard;
    private ManagementPage management;
    // The Script Store's background catalogue ticker, and the user's favourite and seen scripts.
    private ScheduledExecutorService catalogueTicker;
    private FavouritesStore favourites;
    private float dpiScale = 1f;

    // Applies a text size change between frames; set once the settings are open.
    private FontRebuild fontRebuild;

    // The one config inspector, shared by both modes and every "Settings" button
    private InspectorDock inspector;

    // Toasts (top-right), fed from the host event bus, and what their buttons do
    private NotificationOverlay notificationOverlay;
    private ToastRoutes toastRoutes;

    // GLFW window handle, for closing the window from the console's `exit`
    private long glfwWindow;

    // Opened before the window, which reads its frame and placement from them
    private HostSettings settings;
    private HostWindow hostWindow;

    // Mode switching and the shared shell (top bar, Normal-mode clients page, status bar)
    private AppMode currentMode = AppMode.NORMAL;
    private final ModeRequest modeRequest = new ModeRequest();
    private Controls ui;
    private ClientBoard board;
    private Shell shell;

    @Override
    protected void configure(Configuration config) {
        settings = HostSettings.openForHost(HostSettings.defaultBaseDir());
        config.setTitle(WINDOW_TITLE);
    }

    @Override
    protected void initWindow(Configuration config) {
        HostWindow.Pending pending = HostWindow.prepare(settings, config);
        super.initWindow(config);
        hostWindow = pending.attach(handle);
    }

    @Override
    protected void initImGui(Configuration config) {
        super.initImGui(config);

        redirectImGuiIniToConfigDir();
        dpiScale = detectDpiScale();
        ui = new Controls(FontLoader.loadAll(dpiScale, UI_FONT_BASE_PX));
        setupTheme();

        textureManager = new TextureManager();
        outputBuffer = new AnsiOutputBuffer();
        PrintStream guiOut = outputBuffer.getPrintStream();
        installLogCapture(guiOut, outputBuffer.getPrintStream());

        ScriptProfileStore profileStore = new ScriptProfileStore();
        ctx.setProfileStore(profileStore);
        ctx.setSettings(settings);
        // Before anything can connect or load scripts, so no toast-worthy event is missed.
        notificationOverlay = new NotificationOverlay(Clock.systemDefaultZone());
        HostToasts.attach(ctx, notificationOverlay);
        ctx.startAlerts();
        currentMode = AppMode.openingIn(settings.get(SettingKeys.START_MODE));
        AutoStartManager autoStartManager = new AutoStartManager(ctx, profileStore, ctx.getSettings());
        ctx.setAutoStartManager(autoStartManager);

        registry = new CommandRegistry();
        registerCommands(registry, profileStore, autoStartManager);

        wireDisplayHooks();
        guiOut.println(AnsiCodes.colorize(BANNER, AnsiCodes.CYAN));

        ctx.initManagementRuntime();
        // The first management load pass, which also starts the scripts that were
        // running when the host last stopped. Loading JARs blocks, so off the render thread.
        executor.submit(this::loadManagementAtStartup);
        autoStartManager.start();

        buildPanels();
        captureGlfwHandle();
    }

    private static void redirectImGuiIniToConfigDir() {
        // Window/dock layout settings ship as imgui.ini, which ImGui writes
        // next to the CWD by default. In the jpackage app-image the CWD
        // varies (and the install dir may be read-only), so park the file
        // alongside every other persistent BotWithUs store under
        // ~/.botwithus/. Must run before any UI frame so ImGui picks it up
        // for both the initial load and subsequent saves.
        Path configDir = Path.of(System.getProperty("user.home"), ".botwithus");
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            log.warn("Could not create {}; imgui.ini will fall back to CWD: {}",
                    configDir, e.getMessage());
            return;
        }
        ImGui.getIO().setIniFilename(configDir.resolve("imgui.ini").toString());
    }

    private static float detectDpiScale() {
        long monitor = GLFW.glfwGetPrimaryMonitor();
        float[] xScale = new float[1];
        float[] yScale = new float[1];
        if (monitor != 0) {
            GLFW.glfwGetMonitorContentScale(monitor, xScale, yScale);
        }
        return Math.max(xScale[0], 1.0f);
    }

    private void setupTheme() {
        // No ViewportsEnable: nothing floats outside the main window any more, so an
        // ImGui window a script opens stays inside it rather than becoming an OS window.
        ImGui.getIO().addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);
        ImGuiTheme.apply(dpiScale);
    }

    private void installLogCapture(PrintStream guiOut, PrintStream guiErr) {
        LogBuffer logBuffer = new LogBuffer();
        wireLogBufferAppender(logBuffer);
        LogCapture logCapture = new LogCapture(logBuffer, guiOut, guiErr);
        logCapture.install();

        ctx = new CliContext(logBuffer, logCapture);
        ctx.loadGroups();
        ctx.loadClients();
        ctx.setStreamManager(new StreamManager(outputBuffer, textureManager, guiOut));
    }

    private void registerCommands(CommandRegistry r, ScriptProfileStore profileStore,
                                  AutoStartManager autoStartManager) {
        r.register(new HelpCommand(r));
        r.register(new ConnectCommand());
        r.register(new PingCommand());
        r.register(new ScriptsCommand());
        r.register(new LogsCommand());
        r.register(new ReloadCommand());
        r.register(new ScreenshotCommand());
        r.register(new GroupCommand());
        r.register(new MountCommand());
        r.register(new UnmountCommand());
        r.register(new StreamCommand());
        r.register(new MetricsCommand());
        r.register(new ProfileCommand());
        r.register(new ConfigCommand(ctx.getSettings()));
        r.register(new ActionsCommand());
        r.register(new EventsCommand());
        r.register(new PlayerCommand());
        r.register(new ClientCommand());
        r.register(new AutoStartCommand(profileStore, autoStartManager, ctx.getSettings()));
        r.register(new ManagementScriptsCommand());
        r.register(new ClearCommand());
        r.register(new ExitCommand());
    }

    private void wireDisplayHooks() {
        // Image display hook
        ctx.setImageDisplay(image -> textureManager.queueOperation(() -> {
            int texId = textureManager.createTexture(image);
            outputBuffer.appendImage(texId, image.getWidth(), image.getHeight());
        }));

        // Progress display hook
        ctx.setProgressDisplay(new CliContext.ProgressDisplay() {
            @Override
            public Object start(String label) {
                return outputBuffer.insertProgress(label);
            }

            @Override
            public void completeWithImage(Object handle, BufferedImage image) {
                // Safe: handle is the OutputLine this same ProgressDisplay returned from start();
                // the interface keeps it opaque so each implementation owns its handle type.
                OutputLine line = (OutputLine) handle;
                textureManager.queueOperation(() -> {
                    int texId = textureManager.createTexture(image);
                    outputBuffer.completeProgressWithImage(line, texId, image.getWidth(), image.getHeight());
                });
            }

            @Override
            public void completeWithError(Object handle, String message) {
                // Safe: handle is the OutputLine this same ProgressDisplay returned from start();
                // the interface keeps it opaque so each implementation owns its handle type.
                OutputLine line = (OutputLine) handle;
                outputBuffer.completeProgressWithText(line, message,
                        ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B);
            }
        });
    }

    private void buildPanels() {
        // Created before the pages so their "Settings" buttons can open it. The
        // console's `scripts config` reaches it from the command thread, which is
        // why the opener only requests and the shell opens it on the next frame.
        Clock clock = Clock.systemDefaultZone();
        InspectorState inspectorState = new InspectorState(clock);
        inspector = new InspectorDock(ui, inspectorState, new LiveInspectorSource(ctx));
        ctx.setConfigPanelOpener(inspectorState.clientScriptOpener());

        // One catalogue for the whole host: the Script Store and Normal mode's
        // "Your subscriptions" group read the same refresher, so there is one fetch loop.
        SdnCatalogueRefresher sdnCatalogue = StoreCatalogue.refresherFor(new SdnCatalogueSource());
        catalogueTicker = StoreCatalogue.tickInBackground(sdnCatalogue);
        SdnInstaller sdnInstaller = new SdnInstaller();
        favourites = FavouritesStore.inUserHome();
        board = new LiveClientBoard(ctx, this::openLogs, clock, sdnCatalogue, sdnInstaller, executor);
        pages = new PageRegistry(buildPages(sdnCatalogue, sdnInstaller));
        shell = new Shell(ui, pages, inspector, notificationOverlay, hostWindow.chrome(ui));
        toastRoutes = new ToastRoutes(key -> board.actions().retryNow(key), this::openLogs,
                () -> openLogs(Optional.empty()));
    }

    private List<Page> buildPages(SdnCatalogueRefresher sdnCatalogue, SdnInstaller sdnInstaller) {
        List<Page> all = new ArrayList<>();
        all.add(managementPage());
        all.add(new ClientsPage(new UserModeRenderer(ui, inspector.state(), this::openManagement), board));
        all.add(dashboardPage());
        all.add(storePage(sdnCatalogue, sdnInstaller));
        all.add(connectionsPage());
        all.add(groupsPage());
        all.add(installedPage(sdnCatalogue, sdnInstaller));
        all.add(settingsPage());
        return all;
    }

    /**
     * The Dashboard over the live host. Also puts every client under the
     * Diagnostics collection switches, which the Dashboard's tables report on.
     */
    private DashboardPage dashboardPage() {
        new MetricsCollection(ctx.getSettings()).bind(ctx);
        Clock clock = Clock.systemDefaultZone();
        CommandConsole console = new CommandConsole(outputBuffer, registry, executor, ctx, this::shutdown);
        String scriptsFolder = folderLine(LocalScriptLoader.scriptsDir()).text();
        LiveDashboardModel model = new LiveDashboardModel(ctx, console, scriptsFolder,
                inspector.state().clientScriptOpener(), board.actions(), clock);
        dashboard = new DashboardPage(ui, model, clock);
        return dashboard;
    }

    /** The Script Store over the host's catalogue; its "Open" goes to Installed scripts. */
    private StorePage storePage(SdnCatalogueRefresher sdnCatalogue, SdnInstaller sdnInstaller) {
        LiveStoreModel model = new LiveStoreModel(sdnCatalogue, sdnInstaller::install,
                sdnInstaller.ledger()::find, sdnInstaller::isDeliveryEnabled, favourites, ctx::getConnections,
                () -> ctx.getLastLoadReport().scripts(),
                task -> Thread.ofVirtual().name("sdn-store-install").start(task), InstantSource.system());
        return new StorePage(ui, model, id -> pages.select(id));
    }

    /**
     * Groups over the live host. Its Start script dialog lists the scripts the
     * Clients board last loaded, off the render thread; every change runs on the
     * console's queue. A member's robot link opens Management.
     */
    private GroupsPage groupsPage() {
        return new GroupsPage(ui, new LiveGroupsModel(ctx, board::catalog, executor, inspector.state()::request,
                this::openManagement, Clock.systemDefaultZone()));
    }

    /**
     * Connections over the live host. Scans run on their own virtual thread, as
     * they probe every pipe; connects and disconnects share the console's queue.
     */
    private ConnectionsPage connectionsPage() {
        LiveConnectionsModel model = new LiveConnectionsModel(ctx, new ConnectCommandPipes(registry, ctx), executor,
                task -> Thread.ofVirtual().name("pipe-scan").start(task), ClipboardHelper::copyToClipboard,
                Clock.systemUTC());
        return new ConnectionsPage(ui, model, id -> pages.select(id));
    }

    /** Installed scripts, over the host's load report, runners, failed-load list and Store ledger. */
    private InstalledPage installedPage(SdnCatalogueRefresher sdnCatalogue, SdnInstaller sdnInstaller) {
        Path scriptsDir = LocalScriptLoader.scriptsDir();
        SecondLine.FolderPath folder = SecondLine.FolderPath.of(scriptsDir, Path.of(""),
                Path.of(System.getProperty("user.home")));
        LiveInstalledModel model = new LiveInstalledModel(new LiveInstalledModel.Deps(ctx, sdnInstaller.ledger(),
                sdnCatalogue::shown, inspector.state()::request, executor, LiveInstalledModel.desktopOpener(executor),
                Clock.systemDefaultZone(), scriptsDir, folder.text(), INSTALLED_VIEW_MAX_AGE));
        return new InstalledPage(ui, model, folder, id -> pages.select(id));
    }

    /**
     * Management over the live host: the management runtime, each script's
     * targets and settings, and the orchestrator audit log. Its Open folder
     * runs on a virtual thread, as the file browser can take a while to answer.
     */
    private ManagementPage managementPage() {
        Path dir = ManagementScriptLoader.managementDirIn(LocalScriptLoader.scriptsDir());
        SecondLine.FolderPath folder = SecondLine.FolderPath.of(dir, Path.of(""),
                Path.of(System.getProperty("user.home")));
        LiveManagementModel model = new LiveManagementModel(new LiveManagementModel.Deps(ctx,
                inspector.state()::request, executor,
                LiveInstalledModel.desktopOpener(task -> Thread.ofVirtual().name("open-folder").start(task)),
                Clock.systemDefaultZone(), dir, folder.text(), MANAGEMENT_VIEW_MAX_AGE));
        management = new ManagementPage(ui, model, folder, id -> pages.select(id));
        return management;
    }

    /** Opens Management on {@code script}, from anywhere: a robot link on a card or a group member. */
    private void openManagement(String script) {
        modeRequest.request(AppMode.ADVANCED);
        pages.select(PageId.MANAGEMENT);
        management.show(script);
    }

    private void loadManagementAtStartup() {
        try {
            ctx.loadManagementAtStartup();
        } catch (RuntimeException e) {
            log.error("The first management load pass failed", e);
        }
    }

    /** Settings over the live host; a text size change there rebuilds the fonts between frames. */
    private SettingsPage settingsPage() {
        HostSettings settings = ctx.getSettings();
        fontRebuild = new FontRebuild(settings, ui, dpiScale, UI_FONT_BASE_PX);
        Path home = Path.of(System.getProperty("user.home"));
        LiveSettingsModel.Places places = new LiveSettingsModel.Places(HostSettings.defaultBaseDir(),
                LocalScriptLoader.scriptsDir(), LiveSettingsModel.exportFolderIn(home), home, Path.of(""));
        LiveSettingsModel.Host host = new LiveSettingsModel.Host(settings,
                Optional.ofNullable(ctx.getProfileStore()), ctx::getConnections, ctx::getIntegrations);
        return new SettingsPage(ui, new LiveSettingsModel(host, places, LiveSettingsModel::openOnDesktop,
                task -> Thread.ofVirtual().name("settings-io").start(task), Clock.systemDefaultZone(),
                fontRebuild::monitorPercent));
    }

    @Override
    protected void startFrame() {
        if (fontRebuild != null) {
            fontRebuild.applyIfDue(imGuiGl3);
        }
        super.startFrame();
    }

    /** A folder as the sidebar's second line shows it, relative to where the host runs from. */
    private static SecondLine folderLine(Path dir) {
        return SecondLine.FolderPath.of(dir, Path.of(""), Path.of(System.getProperty("user.home")));
    }

    private void captureGlfwHandle() {
        glfwWindow = GLFW.glfwGetCurrentContext();
        var oldSizeCb = GLFW.glfwSetWindowSizeCallback(glfwWindow, null);
        if (oldSizeCb != null) {
            oldSizeCb.free();
        }
    }

    @Override
    public void process() {
        // Execute queued GL operations (texture create/delete)
        textureManager.processPending();

        // openLogs() runs inside render (card "View log", toast actions), so it
        // requests the switch rather than setting currentMode, which the render's
        // own result would overwrite.
        currentMode = modeRequest.resolve(shell.render(currentMode, board, toastRoutes));

        hostWindow.endFrame();
    }

    private static final String LOG_BUFFER_APPENDER_NAME = "LOG_BUFFER";

    /**
     * Looks up the {@link LogBufferAppender} instance Logback created from
     * {@code logback.xml} and wires it to the given buffer. The cast from
     * SLF4J's {@code ILoggerFactory} to Logback's {@link LoggerContext}
     * and the type test on the looked-up {@code Appender} are forced by
     * the SLF4J/Logback binding boundary — both APIs are owned externally
     * and expose loose return types we cannot narrow. They are isolated
     * here, the one place this seam is crossed.
     * <p>
     * The Logback {@code Logger} type below is fully qualified to avoid a
     * name collision with the imported {@link org.slf4j.Logger}.
     */
    private static void wireLogBufferAppender(LogBuffer logBuffer) {
        // rule-exception: {rule:no-casts} — SLF4J/Logback binding boundary.
        // getILoggerFactory() is typed ILoggerFactory and Logback's concrete impl
        // is LoggerContext; there is no cast-free path. Concentrated to one site.
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        // ch.qos.logback.classic.Logger fully qualified: name collision with org.slf4j.Logger
        ch.qos.logback.classic.Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        Appender<ILoggingEvent> appender = root.getAppender(LOG_BUFFER_APPENDER_NAME);
        if (appender instanceof LogBufferAppender lba) {
            lba.setLogBuffer(logBuffer);
        } else {
            log.warn("Appender '{}' not found or not a LogBufferAppender; GUI log capture disabled.",
                    LOG_BUFFER_APPENDER_NAME);
        }
    }

    /** "View log" on a card: the Logs tab scoped to the pipe {@code client} is on, or to every client. */
    private void openLogs(ClientKey client) {
        openLogs(ctx.getClientRegistry().get(client).flatMap(ClientRecord::pipe));
    }

    /** Switches to Advanced, Dashboard, Logs tab, scoped to {@code clientId} or to every client. */
    private void openLogs(Optional<String> clientId) {
        modeRequest.request(AppMode.ADVANCED);
        pages.select(PageId.DASHBOARD);
        dashboard.openLogs(clientId);
    }

    private void shutdown() {
        // Save auto-start state before disconnecting
        if (ctx.getAutoStartManager() != null) {
            ctx.getAutoStartManager().saveAllState();
            ctx.getAutoStartManager().stop();
        }
        if (ctx.getStreamManager() != null) {
            ctx.getStreamManager().stopAll(name -> {
                for (var c : ctx.getConnections()) {
                    if (c.getName().equals(name)) {
                        return c;
                    }
                }
                return null;
            });
        }
        if (ctx.getManagementRuntime() != null) {
            ctx.getManagementRuntime().stopAll();
        }
        ctx.disconnectAll();
        ctx.saveClients();
        ctx.stopAlerts();
        ctx.closeGamevals();
        if (catalogueTicker != null) {
            catalogueTicker.shutdownNow();
        }
        executor.shutdownNow();
        if (glfwWindow != 0) {
            GLFW.glfwSetWindowShouldClose(glfwWindow, true);
        }
    }

    /**
     * Returns the CLI context for use by other components (e.g., the blueprint editor).
     */
    public CliContext getCliContext() {
        return ctx;
    }

    public static void main(String[] args) {
        launch(new ImGuiApp());
    }
}
