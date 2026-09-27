package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.AutoStartManager;
import com.botwithus.bot.cli.CliContext;
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
import com.botwithus.bot.cli.gui.nav.Page;
import com.botwithus.bot.cli.gui.nav.PageId;
import com.botwithus.bot.cli.gui.nav.PageRegistry;
import com.botwithus.bot.cli.gui.nav.SecondLine;
import com.botwithus.bot.cli.gui.notify.Notification;
import com.botwithus.bot.cli.gui.notify.NotificationOverlay;
import com.botwithus.bot.cli.gui.pages.ClientsPage;
import com.botwithus.bot.cli.gui.pages.LegacyPanelPage;
import com.botwithus.bot.cli.gui.pages.StoreSignInLine;
import com.botwithus.bot.cli.gui.usermode.UserModeRenderer;
import com.botwithus.bot.cli.gui.usermode.board.ClientBoard;
import com.botwithus.bot.cli.gui.usermode.board.LiveClientBoard;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogBufferAppender;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.cli.output.AnsiCodes;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.stream.StreamManager;
import com.botwithus.bot.core.config.ScriptProfileStore;
import com.botwithus.bot.core.sdn.SdnCatalogueRefresher;
import com.botwithus.bot.core.sdn.SdnCatalogueSource;
import com.botwithus.bot.core.sdn.SdnInstaller;
import com.botwithus.bot.core.runtime.LocalScriptLoader;
import com.botwithus.bot.core.runtime.ManagementScriptLoader;
import com.botwithus.bot.core.runtime.ScriptRunner;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main imgui-based application. The shell draws Normal mode's Clients page, or
 * Advanced mode's sidebar and the selected page from the {@link PageRegistry}.
 */
public class ImGuiApp extends Application {

    public ImGuiApp() {}

    private static final Logger log = LoggerFactory.getLogger(ImGuiApp.class);

    private static final float UI_FONT_BASE_PX = 17f;
    /** Initial GLFW window width (px). */
    private static final int APP_WINDOW_DEFAULT_WIDTH = 1100;
    /** Initial GLFW window height (px). */
    private static final int APP_WINDOW_DEFAULT_HEIGHT = 700;

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

    // Pages. The Dashboard is kept so "View log" can bring its Logs tab forward.
    private PageRegistry pages;
    private LegacyPanelPage dashboard;
    private SdnScriptsPanel sdnScriptsPanel;
    private GuiPanel logsPanel;
    private float dpiScale = 1f;


    // Script custom UI window (floating window)
    private ScriptUIWindow scriptUIWindow;

    // Script config-field editor (floating window) — used for scripts that
    // expose ConfigFields but no custom ScriptUI.
    private ScriptConfigPanel scriptConfigPanel;

    // Management script config panel (floating window)
    private ManagementConfigPanel managementConfigPanel;

    // Toast/banner overlay (event-driven, fixed-position, top-right)
    private NotificationOverlay notificationOverlay;

    // GLFW window handle for title updates
    private long glfwWindow;

    // Mode switching and the shared shell (top bar, Normal-mode clients page, status bar)
    private AppMode currentMode = AppMode.NORMAL;
    private final ModeRequest modeRequest = new ModeRequest();
    private Controls ui;
    private ClientBoard board;
    private Shell shell;

    @Override
    protected void configure(Configuration config) {
        config.setTitle("BotWithUs \u2014 disconnected");
        config.setWidth(APP_WINDOW_DEFAULT_WIDTH);
        config.setHeight(APP_WINDOW_DEFAULT_HEIGHT);
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
        ctx.setSettings(HostSettings.openForHost(HostSettings.defaultBaseDir()));
        AutoStartManager autoStartManager = new AutoStartManager(ctx, profileStore, ctx.getSettings());
        ctx.setAutoStartManager(autoStartManager);

        registry = new CommandRegistry();
        registerCommands(registry, profileStore, autoStartManager);

        wireDisplayHooks();
        guiOut.println(AnsiCodes.colorize(BANNER, AnsiCodes.CYAN));

        ctx.initManagementRuntime();
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
        ImGui.getIO().addConfigFlags(ImGuiConfigFlags.ViewportsEnable | ImGuiConfigFlags.NavEnableKeyboard);
        ImGuiTheme.apply(dpiScale);
    }

    private void installLogCapture(PrintStream guiOut, PrintStream guiErr) {
        LogBuffer logBuffer = new LogBuffer();
        wireLogBufferAppender(logBuffer);
        LogCapture logCapture = new LogCapture(logBuffer, guiOut, guiErr);
        logCapture.install();

        ctx = new CliContext(logBuffer, logCapture);
        ctx.loadGroups();
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
        // Floating windows (created before opener wiring so the lambdas can capture them).
        // Advanced mode's Configure buttons still open these; Normal mode uses the
        // docked inspector on the clients page instead.
        scriptUIWindow = new ScriptUIWindow();
        scriptConfigPanel = new ScriptConfigPanel();

        ctx.setConfigPanelOpener(this::openScriptConfig);
        managementConfigPanel = new ManagementConfigPanel();

        // Notification overlay (event-driven). Subscribed to each connection's
        // event bus the moment connect() succeeds.
        Clock clock = Clock.systemDefaultZone();
        notificationOverlay = new NotificationOverlay(clock, this::accountOf);
        // One catalogue for the whole host: the Scripts Store panel and Normal mode's
        // "Your subscriptions" group read the same refresher, so there is one fetch loop.
        SdnCatalogueRefresher sdnCatalogue = SdnScriptsPanel.catalogueRefresher(new SdnCatalogueSource());
        SdnInstaller sdnInstaller = new SdnInstaller();
        board = new LiveClientBoard(ctx, clientId -> openLogs(), clock, sdnCatalogue, sdnInstaller, executor);
        pages = new PageRegistry(buildPages(sdnCatalogue, sdnInstaller));
        shell = new Shell(ui, pages, notificationOverlay);
        ctx.setOnConnect(conn -> {
            if (conn.getEventBus() != null) {
                notificationOverlay.subscribeTo(conn.getEventBus());
            }
        });
    }

    /**
     * The Advanced pages. Until each redesigned page lands, the pre-redesign
     * panels are hosted as interim pages so nothing goes missing: Console, Logs
     * and Diagnostics are the Dashboard's tabs, and Script UI sits beside the
     * Scripts panel under Installed scripts.
     */
    private List<LegacyPanelPage> legacyPages(SdnCatalogueRefresher sdnCatalogue, SdnInstaller sdnInstaller) {
        logsPanel = new LogsPanel();
        dashboard = new LegacyPanelPage(PageId.DASHBOARD, ui, ctx,
                List.of(new ConsolePanel(outputBuffer, registry, executor, this::shutdown), logsPanel,
                        new DiagnosticsPanel()), Optional::empty);
        ManagementScriptsPanel mgmtPanel = new ManagementScriptsPanel(executor);
        mgmtPanel.setConfigOpener(runner -> managementConfigPanel.open(runner));
        sdnScriptsPanel = new SdnScriptsPanel(executor, sdnCatalogue, sdnInstaller);
        Path scriptsDir = LocalScriptLoader.scriptsDir();
        Optional<SecondLine> scriptsLine = Optional.of(folderLine(scriptsDir));
        Optional<SecondLine> managementLine = Optional.of(folderLine(ManagementScriptLoader.managementDirIn(scriptsDir)));
        return List.of(
                dashboard,
                LegacyPanelPage.of(PageId.CONNECTIONS, ui, ctx, new ConnectionsPanel(executor, registry)),
                LegacyPanelPage.of(PageId.GROUPS, ui, ctx, new GroupsPanel()),
                new LegacyPanelPage(PageId.INSTALLED, ui, ctx,
                        List.of(new ScriptsPanel(executor), new ScriptUIPanel()), () -> scriptsLine),
                new LegacyPanelPage(PageId.MANAGEMENT, ui, ctx, List.of(mgmtPanel), () -> managementLine),
                new LegacyPanelPage(PageId.STORE, ui, ctx, List.of(sdnScriptsPanel),
                        () -> Optional.of(StoreSignInLine.of(sdnCatalogue.shown()))),
                LegacyPanelPage.of(PageId.SETTINGS, ui, ctx, new SettingsPanel()));
    }

    private List<Page> buildPages(SdnCatalogueRefresher sdnCatalogue, SdnInstaller sdnInstaller) {
        List<Page> all = new ArrayList<>(legacyPages(sdnCatalogue, sdnInstaller));
        all.add(new ClientsPage(new UserModeRenderer(ui), board));
        return all;
    }

    /** A folder as the sidebar's second line shows it, relative to where the host runs from. */
    private static SecondLine folderLine(Path dir) {
        return SecondLine.FolderPath.of(dir, Path.of(""), Path.of(System.getProperty("user.home")));
    }

    /**
     * Routes the "Configure" action on a running script to whichever floating window
     * fits the script's surface: the custom {@link com.botwithus.bot.api.ui.ScriptUI}
     * if the script provides one, otherwise the generic config-field editor.
     * The card surfaces the button when either is present, so without this routing
     * config-only scripts open a window that immediately closes itself.
     */
    private void openScriptConfig(ScriptRunner runner) {
        if (runner == null) {
            return;
        }
        var fields = runner.getConfigFields();
        boolean hasFields = fields != null && !fields.isEmpty();
        if (hasFields) {
            // The config panel renders the ConfigFields (with Apply/persist) AND,
            // below them, the script's custom getUI() if it has one — so a script
            // that provides both shows both here instead of the custom UI hiding the
            // settings. UI-only scripts (no fields) still get the dedicated window.
            scriptConfigPanel.open(runner);
        } else if (runner.getScript().getUI() != null) {
            scriptUIWindow.open(runner);
        }
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
        currentMode = modeRequest.resolve(shell.render(currentMode, board, this::onToastAction));

        // Render script custom UI as a floating window (outside the main window)
        if (scriptUIWindow != null && scriptUIWindow.isOpen()) {
            scriptUIWindow.render();
        }

        // Render script config-field editor as a floating window
        if (scriptConfigPanel != null && scriptConfigPanel.isOpen()) {
            scriptConfigPanel.render();
        }

        // Render management script config panel as a floating window
        if (managementConfigPanel != null && managementConfigPanel.isOpen()) {
            managementConfigPanel.render();
        }

        // Update window title based on connection state
        updateTitle();
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

    /** Switches to Advanced, Dashboard, Logs tab, where script and connection logs are shown. */
    private void openLogs() {
        modeRequest.request(AppMode.ADVANCED);
        pages.select(PageId.DASHBOARD);
        dashboard.show(logsPanel);
    }

    private void onToastAction(Notification n) {
        switch (n.kind()) {
            case GAVE_UP -> board.actions().reconnect(n.subject());
            case SCRIPT_CRASHED, LOAD_FAILED -> openLogs();
            case CONNECTION_LOST, RECONNECTING, RECONNECTED -> { }
        }
    }

    /** The account playing on connection {@code name}, or the name itself when unknown. */
    private String accountOf(String name) {
        for (var conn : new ArrayList<>(ctx.getConnections())) {
            if (conn.getName().equals(name) && conn.getAccountName() != null) {
                return conn.getAccountName();
            }
        }
        return name;
    }

    private void updateTitle() {
        if (glfwWindow == 0) {
            return;
        }
        boolean connected = ctx.hasActiveConnection();
        String connName = ctx.getActiveConnectionName();
        int count = ctx.getConnections().size();

        String title;
        if (connected && connName != null) {
            String suffix = count > 1 ? " [" + count + "]" : "";
            title = "BotWithUs \u2014 " + connName + suffix;
        } else {
            title = "BotWithUs \u2014 disconnected";
        }
        GLFW.glfwSetWindowTitle(glfwWindow, title);
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
        ctx.closeGamevals();
        if (sdnScriptsPanel != null) {
            sdnScriptsPanel.close();
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
