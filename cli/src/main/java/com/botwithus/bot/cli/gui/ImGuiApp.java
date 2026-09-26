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
import com.botwithus.bot.cli.config.CliConfig;
import com.botwithus.bot.cli.gui.notify.Notification;
import com.botwithus.bot.cli.gui.notify.NotificationOverlay;
import com.botwithus.bot.cli.gui.usermode.UserModeRenderer;
import com.botwithus.bot.cli.gui.usermode.board.ClientBoard;
import com.botwithus.bot.cli.gui.usermode.board.LiveClientBoard;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogBufferAppender;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.cli.output.AnsiCodes;
import com.botwithus.bot.cli.stream.StreamManager;
import com.botwithus.bot.core.config.ScriptProfileStore;
import com.botwithus.bot.core.runtime.ScriptRunner;

import imgui.ImGui;
import imgui.app.Application;
import imgui.app.Configuration;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiCol;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main imgui-based application with tabbed GUI panels.
 * Each tab renders via the {@link GuiPanel} interface.
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

    // Panels
    private final List<GuiPanel> panels = new ArrayList<>();
    private SdnScriptsPanel sdnScriptsPanel;
    private GuiPanel logsPanel;
    private int selectedPanel = 0;
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
        AutoStartManager autoStartManager = new AutoStartManager(ctx, profileStore);
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
        r.register(new ConfigCommand(CliConfig.defaults()));
        r.register(new ActionsCommand());
        r.register(new EventsCommand());
        r.register(new PlayerCommand());
        r.register(new ClientCommand());
        r.register(new AutoStartCommand(profileStore, autoStartManager));
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
        board = new LiveClientBoard(ctx, clientId -> openLogs(), clock);
        shell = new Shell(ui, new UserModeRenderer(ui), notificationOverlay);
        ctx.setOnConnect(conn -> {
            if (conn.getEventBus() != null) {
                notificationOverlay.subscribeTo(conn.getEventBus());
            }
        });

        panels.add(new ConsolePanel(outputBuffer, registry, executor, this::shutdown));
        panels.add(new ConnectionsPanel(executor, registry));
        panels.add(new ScriptsPanel(executor));
        ManagementScriptsPanel mgmtPanel = new ManagementScriptsPanel(executor);
        mgmtPanel.setConfigOpener(runner -> managementConfigPanel.open(runner));
        panels.add(mgmtPanel);
        panels.add(new ScriptUIPanel());
        logsPanel = new LogsPanel();
        panels.add(logsPanel);
        panels.add(new GroupsPanel());
        panels.add(new DiagnosticsPanel());
        panels.add(new SettingsPanel());
        // Appended last on purpose: NAV_SECTION_PANELS and NAV_ICONS index into
        // this list positionally, so inserting anywhere else renumbers every
        // panel after it.
        sdnScriptsPanel = new SdnScriptsPanel(executor);
        panels.add(sdnScriptsPanel);
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

        currentMode = shell.render(currentMode, board, this::renderDeveloperMode, this::onToastAction);

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

    /**
     * Render the full Advanced mode UI with sidebar navigation and panels.
     */
    private void renderDeveloperMode() {
        // Sidebar width: icon + longest label + padding
        float sidebarWidth = ImGui.getFrameHeight() + ImGui.calcTextSize("Management").x
                + ImGui.getStyle().getWindowPaddingX() * 2 + 48f;
        float contentHeight = ImGui.getContentRegionAvailY();

        // --- Sidebar Navigation ---
        ImGui.pushStyleColor(ImGuiCol.ChildBg,
                ImGuiTheme.SIDEBAR_BG_R, ImGuiTheme.SIDEBAR_BG_G, ImGuiTheme.SIDEBAR_BG_B, 1f);
        ImGui.pushStyleColor(ImGuiCol.Border,
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.3f);
        ImGui.beginChild("##sidebar", sidebarWidth, contentHeight, true);
        ImGui.popStyleColor(2);
        renderSidebar();
        ImGui.endChild();

        ImGui.sameLine(0, 0);

        // --- Content Area ---
        ImGui.beginChild("##content", 0, contentHeight, false);
        ImGui.spacing();
        if (selectedPanel >= 0 && selectedPanel < panels.size()) {
            panels.get(selectedPanel).render(ctx);
        }
        ImGui.endChild();
    }

    // Sidebar navigation section definitions
    private static final String[] NAV_SECTION_LABELS = {"CORE", "EXTENSIONS", "SYSTEM"};
    private static final int[][] NAV_SECTION_PANELS = {
        {0, 1, 2},      // Console, Connections, Scripts
        {3, 4, 6, 9},   // Management, Script UI, Groups, Scripts Store
        {5, 7, 8}       // Logs, Diagnostics, Settings
    };
    // Font Awesome icons for each panel (matching panel order in the panels list)
    private static final String[] NAV_ICONS = {
        Icons.TERMINAL,     // 0 Console
        Icons.PLUG,         // 1 Connections
        Icons.CODE,         // 2 Scripts
        Icons.ROBOT,        // 3 Management
        Icons.WINDOW,       // 4 Script UI
        Icons.LIST,         // 5 Logs
        Icons.LAYER_GROUP,  // 6 Groups
        Icons.CHART,        // 7 Diagnostics
        Icons.GEAR,         // 8 Settings
        Icons.DOWNLOAD,     // 9 Scripts Store
    };

    private void renderSidebar() {
        float fontH = ImGui.getFontSize();
        float indent = ImGui.getStyle().getWindowPaddingX() * 0.5f;

        // The brand mark lives in the shared top bar now; the sidebar starts
        // straight at its first section.
        ImGui.dummy(0f, fontH * 0.4f);
        renderNavigation(fontH, indent);
    }

    private void renderNavigation(float fontH, float indent) {
        for (int s = 0; s < NAV_SECTION_LABELS.length; s++) {
            ImGui.dummy(0f, fontH * 0.6f);
            ImGui.setCursorPosX(ImGui.getCursorPosX() + indent);
            ImGui.textColored(
                    ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 0.55f,
                    NAV_SECTION_LABELS[s]);
            ImGui.dummy(0f, fontH * 0.15f);

            for (int p : NAV_SECTION_PANELS[s]) {
                if (p >= panels.size()) {
                    continue;
                }
                renderNavItem(p, fontH, indent);
            }
        }
    }

    private void renderNavItem(int p, float fontH, float indent) {
        boolean isActive = (p == selectedPanel);

        // Per-item animated hover weight, plus eased "active" animation
        // for the left accent bar to slide into place.
        String hoverKey = "nav:h:" + p;
        String activeKey = "nav:a:" + p;

        // Transparent selectable (we'll draw our own background + accent)
        ImGui.pushStyleColor(ImGuiCol.Header, 0f, 0f, 0f, 0f);
        ImGui.pushStyleColor(ImGuiCol.HeaderHovered, 0f, 0f, 0f, 0f);
        ImGui.pushStyleColor(ImGuiCol.HeaderActive, 0f, 0f, 0f, 0f);

        String icon = p < NAV_ICONS.length ? NAV_ICONS[p] : "";
        // leading space reserved for the accent bar + icon gutter
        String label = "    " + icon + "   " + panels.get(p).title() + "##nav" + p;

        if (ImGui.selectable(label, isActive)) {
            selectedPanel = p;
        }
        boolean hovered = ImGui.isItemHovered();
        float hoverT = Motion.hover(hoverKey, hovered);
        float activeT = Motion.step(activeKey, isActive ? 1f : 0f, 14f);

        ImGui.popStyleColor(3);
        drawNavItemAccent(fontH, indent, hoverT, activeT);
    }

    private static void drawNavItemAccent(float fontH, float indent, float hoverT, float activeT) {
        var draw = ImGui.getWindowDrawList();
        // Custom-drawn row background
        float x0 = ImGui.getItemRectMinX();
        float y0 = ImGui.getItemRectMinY();
        float x1 = ImGui.getItemRectMaxX();
        float y1 = ImGui.getItemRectMaxY();
        float rowH = y1 - y0;
        float rounding = fontH * 0.3f;

        // Hover wash (fades in), active tint (stronger)
        float bgAlpha = 0.05f * hoverT + 0.12f * activeT;
        if (bgAlpha > 0.001f) {
            int bg = ImGuiTheme.imCol32(
                    ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, bgAlpha);
            draw.addRectFilled(x0 + indent * 0.25f, y0, x1 - indent * 0.25f, y1,
                    bg, rounding);
        }

        // Left accent bar — height animates with activeT (Motion eases it in)
        if (activeT > 0.02f) {
            float barPadY = rowH * 0.18f;
            float fullH = rowH - barPadY * 2f;
            float h = fullH * Motion.easeOutCubic(activeT);
            float by0 = y0 + (rowH - h) * 0.5f;
            float bw = Math.max(2.5f, fontH * 0.2f);
            int col = ImGuiTheme.imCol32(
                    ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, activeT);
            draw.addRectFilled(x0 + indent * 0.25f, by0,
                    x0 + indent * 0.25f + bw, by0 + h, col, bw * 0.5f);
        }
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

    /** Switches to Advanced, Logs panel, where script and connection logs are shown. */
    private void openLogs() {
        currentMode = AppMode.ADVANCED;
        selectedPanel = panels.indexOf(logsPanel);
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
