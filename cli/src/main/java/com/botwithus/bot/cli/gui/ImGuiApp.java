package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.AutoStartManager;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.blueprint.BlueprintEditor;
import com.botwithus.bot.cli.command.CommandRegistry;
import com.botwithus.bot.cli.command.impl.*;
import com.botwithus.bot.cli.gui.loader.LoaderScreen;
import com.botwithus.bot.cli.gui.usermode.UserAccountsRenderer;
import com.botwithus.bot.cli.gui.usermode.UserModeRenderer;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogBufferAppender;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.cli.output.AnsiCodes;
import com.botwithus.bot.cli.stream.StreamManager;
import com.botwithus.bot.core.config.ScriptProfileStore;
import com.botwithus.bot.core.loader.BwuClient;

import imgui.ImFontAtlas;
import imgui.ImFontConfig;
import imgui.ImGui;
import imgui.app.Application;
import imgui.app.Configuration;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiWindowFlags;

import org.lwjgl.glfw.GLFW;

import java.awt.image.BufferedImage;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main imgui-based application with tabbed GUI panels.
 * Each tab renders via the {@link GuiPanel} interface.
 */
public class ImGuiApp extends Application {

    private static final String BANNER = """

            ____        _ __        ___ _   _     _   _
           | __ )  ___ | |\\ \\      / (_) |_| |__ | | | |___
           |  _ \\ / _ \\| __\\ \\ /\\ / /| | __| '_ \\| | | / __|
           | |_) | (_) | |_ \\ V  V / | | |_| | | | |_| \\__ \\
           |____/ \\___/ \\__| \\_/\\_/  |_|\\__|_| |_|\\___/|___/
                        Script Manager

              Type 'help' for available commands.
              Press F2 to open the Blueprint Editor.
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
    private StatusBar statusBar;
    private int selectedPanel = 0;
    private float dpiScale = 1f;

    // Blueprint editor mode
    private boolean editorMode = false;
    private BlueprintEditor blueprintEditor;

    // Script custom UI window (floating window)
    private ScriptUIWindow scriptUIWindow;

    // Management script config panel (floating window)
    private ManagementConfigPanel managementConfigPanel;

    // GLFW window handle for title updates
    private long glfwWindow;

    // Mode switching
    private AppMode currentMode = AppMode.LAUNCHER;
    private TopBar topBar;
    private UserModeRenderer userModeRenderer;
    private UserAccountsRenderer launcherRenderer;

    // Loader screen (shown before main app)
    private LoaderScreen loaderScreen;

    @Override
    protected void configure(Configuration config) {
        config.setTitle("BotWithUs \u2014 disconnected");
        config.setWidth(1100);
        config.setHeight(700);
    }

    @Override
    protected void initImGui(Configuration config) {
        super.initImGui(config);

        // Detect monitor DPI scale via GLFW content scale
        long monitor = GLFW.glfwGetPrimaryMonitor();
        float[] xScale = new float[1];
        float[] yScale = new float[1];
        if (monitor != 0) {
            GLFW.glfwGetMonitorContentScale(monitor, xScale, yScale);
        }
        dpiScale = Math.max(xScale[0], 1.0f);

        float uiSize = (float) Math.round(17f * dpiScale);
        ImFontAtlas atlas = ImGui.getIO().getFonts();
        atlas.clear();

        // Primary UI font: Inter (bundled), fallback to system font
        byte[] uiFont = loadResourceFont("/fonts/Inter-Regular.ttf");
        if (uiFont == null) {
            uiFont = loadSystemFont("segoeui.ttf", "arial.ttf", "verdana.ttf");
        }
        ImFontConfig cfg = new ImFontConfig();
        cfg.setOversampleH(3);
        cfg.setOversampleV(3);
        cfg.setPixelSnapH(true);
        if (uiFont != null) {
            atlas.addFontFromMemoryTTF(uiFont, uiSize, cfg);
        } else {
            cfg.setSizePixels(uiSize);
            atlas.addFontDefault(cfg);
        }

        // Merge Font Awesome icons into the primary font
        byte[] iconFont = loadResourceFont("/fonts/fa-solid-900.ttf");
        if (iconFont != null) {
            ImFontConfig iconCfg = new ImFontConfig();
            iconCfg.setMergeMode(true);
            iconCfg.setPixelSnapH(true);
            iconCfg.setOversampleH(2);
            iconCfg.setOversampleV(2);
            // FA6 solid range: U+F000..U+F8FF + extended U+E000..U+E4FF
            short[] iconRanges = {(short) 0xE000, (short) 0xF8FF, 0};
            atlas.addFontFromMemoryTTF(iconFont, uiSize * 0.85f, iconCfg, iconRanges);
            iconCfg.destroy();
        }

        cfg.destroy();
        atlas.build();

        ImGui.getIO().addConfigFlags(ImGuiConfigFlags.ViewportsEnable);

        ImGuiTheme.apply(dpiScale);

        textureManager = new TextureManager();
        outputBuffer = new AnsiOutputBuffer();

        PrintStream guiOut = outputBuffer.getPrintStream();
        PrintStream guiErr = outputBuffer.getPrintStream();

        LogBuffer logBuffer = new LogBuffer();
        LogBufferAppender.setLogBuffer(logBuffer);
        LogCapture logCapture = new LogCapture(logBuffer, guiOut, guiErr);
        logCapture.install();

        ctx = new CliContext(logBuffer, logCapture);
        ctx.loadGroups();
        ctx.setStreamManager(new StreamManager(outputBuffer, textureManager, guiOut));

        ScriptProfileStore profileStore = new ScriptProfileStore();
        ctx.setProfileStore(profileStore);
        AutoStartManager autoStartManager = new AutoStartManager(ctx, profileStore);
        ctx.setAutoStartManager(autoStartManager);

        registry = new CommandRegistry();
        registry.register(new HelpCommand(registry));
        registry.register(new ConnectCommand());
        registry.register(new PingCommand());
        registry.register(new ScriptsCommand());
        registry.register(new LogsCommand());
        registry.register(new ReloadCommand());
        registry.register(new ScreenshotCommand());
        registry.register(new GroupCommand());
        registry.register(new MountCommand());
        registry.register(new UnmountCommand());
        registry.register(new StreamCommand());
        registry.register(new MetricsCommand());
        registry.register(new ProfileCommand());
        registry.register(new ConfigCommand(com.botwithus.bot.cli.config.CliConfig.defaults()));
        registry.register(new ActionsCommand());
        registry.register(new EventsCommand());
        registry.register(new ClientCommand());
        registry.register(new AutoStartCommand(profileStore, autoStartManager));
        registry.register(new ManagementScriptsCommand());
        registry.register(new ClearCommand());
        registry.register(new ExitCommand());

        // Image display hook
        ctx.setImageDisplay(image -> {
            textureManager.queueOperation(() -> {
                int texId = textureManager.createTexture(image);
                outputBuffer.appendImage(texId, image.getWidth(), image.getHeight());
            });
        });

        // Progress display hook
        ctx.setProgressDisplay(new CliContext.ProgressDisplay() {
            @Override
            public Object start(String label) {
                return outputBuffer.insertProgress(label);
            }

            @Override
            public void completeWithImage(Object handle, BufferedImage image) {
                OutputLine line = (OutputLine) handle;
                textureManager.queueOperation(() -> {
                    int texId = textureManager.createTexture(image);
                    outputBuffer.completeProgressWithImage(line, texId, image.getWidth(), image.getHeight());
                });
            }

            @Override
            public void completeWithError(Object handle, String message) {
                OutputLine line = (OutputLine) handle;
                outputBuffer.completeProgressWithText(line, message,
                        ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B);
            }
        });

        // Print banner
        guiOut.println(AnsiCodes.colorize(BANNER, AnsiCodes.CYAN));

        // Load bwu.dll once — shared between LoaderScreen and management runtime
        BwuClient bwu = null;
        var dllPath = BwuClient.resolve(getClass());
        if (dllPath != null) {
            bwu = BwuClient.load(dllPath).orElse(null);
        }
        if (bwu != null) {
            bwu.init();
        }

        // Initialize management script runtime with the shared BwuClient
        ctx.initManagementRuntime(bwu);

        // Start auto-connect scanning if enabled
        autoStartManager.start();

        // Initialize loader screen with the same BwuClient instance
        loaderScreen = new LoaderScreen(bwu);

        // Initialize top bar and mode renderers
        topBar = new TopBar();
        userModeRenderer = new UserModeRenderer();
        userModeRenderer.setConfigPanelOpener(runner -> scriptUIWindow.open(runner));

        // Launcher mode (account management)
        launcherRenderer = new UserAccountsRenderer();
        launcherRenderer.setBwuClient(bwu);
        launcherRenderer.setExecutor(executor);

        // Initialize blueprint editor
        blueprintEditor = new BlueprintEditor();

        // Initialize script UI window and wire opener
        scriptUIWindow = new ScriptUIWindow();
        ctx.setConfigPanelOpener(runner -> scriptUIWindow.open(runner));

        // Initialize management config panel
        managementConfigPanel = new ManagementConfigPanel();

        // Initialize panels
        panels.add(new ConsolePanel(outputBuffer, registry, executor, this::shutdown));
        panels.add(new ConnectionsPanel(executor, registry));
        panels.add(new AccountsPanel(bwu, executor));
        panels.add(new ScriptsPanel(executor));
        ManagementScriptsPanel mgmtPanel = new ManagementScriptsPanel(executor);
        mgmtPanel.setConfigOpener(runner -> managementConfigPanel.open(runner));
        panels.add(mgmtPanel);
        panels.add(new ScriptUIPanel());
        panels.add(new LogsPanel());
        panels.add(new GroupsPanel());
        panels.add(new SettingsPanel());

        statusBar = new StatusBar();

        glfwWindow = GLFW.glfwGetCurrentContext();

        var oldSizeCb = GLFW.glfwSetWindowSizeCallback(glfwWindow, null);
        if (oldSizeCb != null) oldSizeCb.free();
    }

    @Override
    public void process() {
        // Execute queued GL operations (texture create/delete)
        textureManager.processPending();

        // --- Loader screen (shown before main app) ---
        if (loaderScreen != null && !loaderScreen.isComplete()) {
            var viewport = ImGui.getMainViewport();
            ImGui.setNextWindowPos(viewport.getPosX(), viewport.getPosY(), ImGuiCond.Always);
            ImGui.setNextWindowSize(viewport.getSizeX(), viewport.getSizeY(), ImGuiCond.Always);

            int loaderFlags = ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoMove
                    | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoBringToFrontOnFocus;
            ImGui.begin("##loader", loaderFlags);
            loaderScreen.render();
            ImGui.end();
            return;
        }

        // Toggle editor mode with F2 (only in advanced mode)
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_F2) && currentMode == AppMode.ADVANCED) {
            editorMode = !editorMode;
            if (!editorMode && blueprintEditor != null) {
                blueprintEditor.dispose();
            }
        }

        // Cycle app mode with F12: Launcher → Normal → Advanced → Launcher
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_F12)) {
            currentMode = switch (currentMode) {
                case LAUNCHER -> AppMode.NORMAL;
                case NORMAL -> AppMode.ADVANCED;
                case ADVANCED -> AppMode.LAUNCHER;
            };
            editorMode = false; // exit editor when switching modes
        }

        // Full-window imgui window — use main viewport pos for correct placement with viewports enabled
        var viewport = ImGui.getMainViewport();
        ImGui.setNextWindowPos(viewport.getPosX(), viewport.getPosY(), ImGuiCond.Always);
        ImGui.setNextWindowSize(viewport.getSizeX(), viewport.getSizeY(), ImGuiCond.Always);

        int windowFlags = ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoBringToFrontOnFocus;

        if (editorMode && currentMode == AppMode.ADVANCED) {
            windowFlags |= ImGuiWindowFlags.MenuBar;
        }

        ImGui.begin("##main", windowFlags);

        // Top bar with mode tabs (always visible, unless in blueprint editor)
        if (!editorMode) {
            AppMode toggled = topBar.render(currentMode, dpiScale, ctx);
            if (toggled != null && toggled != currentMode) {
                currentMode = toggled;
            }
        }

        // Route to the appropriate mode renderer
        if (editorMode && currentMode == AppMode.ADVANCED) {
            try {
                blueprintEditor.render();
            } catch (Exception e) {
                editorMode = false;
                outputBuffer.getPrintStream().println("Blueprint editor error: " + e.getMessage());
                e.printStackTrace();
                blueprintEditor.dispose();
            }
        } else {
            switch (currentMode) {
                case LAUNCHER -> renderLauncherMode();
                case NORMAL -> renderUserMode();
                case ADVANCED -> renderDeveloperMode();
            }
        }

        ImGui.end();

        // Render script custom UI as a floating window (outside the main window)
        if (scriptUIWindow != null && scriptUIWindow.isOpen()) {
            scriptUIWindow.render();
        }

        // Render management script config panel as a floating window
        if (managementConfigPanel != null && managementConfigPanel.isOpen()) {
            managementConfigPanel.render();
        }

        // Update window title based on connection state
        updateTitle();
    }

    /**
     * Render the Launcher tab — account management (add/launch game accounts).
     */
    private void renderLauncherMode() {
        float availHeight = ImGui.getContentRegionAvailY();
        ImGui.beginChild("##launcher", 0, availHeight, false);
        launcherRenderer.render();
        ImGui.endChild();
    }

    /**
     * Render the full Advanced mode UI with sidebar navigation and panels.
     */
    private void renderDeveloperMode() {
        // Reserve space for status bar at the bottom
        float statusBarHeight = ImGui.getFrameHeightWithSpacing() + 8f;
        // Sidebar width: icon + longest label + padding
        float sidebarWidth = ImGui.getFrameHeight() + ImGui.calcTextSize("Management").x
                + ImGui.getStyle().getWindowPaddingX() * 2 + 48f;
        float contentHeight = ImGui.getContentRegionAvailY() - statusBarHeight;

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

        // Status bar at the bottom
        ImGui.spacing();
        statusBar.render(ctx);
    }

    /**
     * Render the simplified user mode dashboard with client cards.
     */
    private void renderUserMode() {
        userModeRenderer.render(ctx);
    }

    // Sidebar navigation section definitions
    private static final String[] NAV_SECTION_LABELS = {"CORE", "EXTENSIONS", "SYSTEM"};
    private static final int[][] NAV_SECTION_PANELS = {
        {0, 1, 2, 3},   // Console, Connections, Accounts, Scripts
        {4, 5, 6},      // Management, Script UI, Groups
        {7, 8}           // Logs, Settings
    };
    // Font Awesome icons for each panel (matching panel order in the panels list)
    private static final String[] NAV_ICONS = {
        Icons.TERMINAL,     // Console
        Icons.PLUG,         // Connections
        Icons.USERS,        // Accounts
        Icons.CODE,         // Scripts
        Icons.ROBOT,        // Management
        Icons.WINDOW,       // Script UI
        Icons.LAYER_GROUP,  // Groups
        Icons.LIST,         // Logs
        Icons.GEAR,         // Settings
    };

    private void renderSidebar() {
        float fontH = ImGui.getFontSize();
        float padX = ImGui.getStyle().getWindowPaddingX();
        float indent = padX * 0.5f;

        ImGui.dummy(0f, fontH * 0.4f);

        var draw = ImGui.getWindowDrawList();
        int accentCol = ImGuiTheme.imCol32(
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 1f);
        int accentDim = ImGuiTheme.imCol32(
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.35f);

        // ── Brand header ──────────────────────────────────────────────
        float logoX = ImGui.getCursorScreenPosX() + indent;
        float logoY = ImGui.getCursorScreenPosY();
        float barW = Math.max(3f, fontH * 0.25f);
        float textH = ImGui.getTextLineHeight();

        // Two-bar brand mark
        draw.addRectFilled(logoX, logoY, logoX + barW, logoY + textH, accentCol, barW * 0.4f);
        draw.addRectFilled(logoX + barW + fontH * 0.15f, logoY + textH * 0.4f,
                logoX + barW * 2f + fontH * 0.15f, logoY + textH, accentDim, barW * 0.4f);

        ImGui.setCursorPosX(ImGui.getCursorPosX() + indent + barW * 2f + fontH * 0.75f);
        ImGui.pushStyleColor(ImGuiCol.Text,
                ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.95f);
        ImGui.text("BotWithUs");
        ImGui.popStyleColor();

        ImGui.setCursorPosX(ImGui.getCursorPosX() + indent + barW * 2f + fontH * 0.75f);
        ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 0.5f,
                "Script Manager");

        ImGui.dummy(0f, fontH * 0.4f);
        GuiHelpers.subtleSeparator();

        // ── Navigation ────────────────────────────────────────────────
        for (int s = 0; s < NAV_SECTION_LABELS.length; s++) {
            ImGui.dummy(0f, fontH * 0.6f);
            ImGui.setCursorPosX(ImGui.getCursorPosX() + indent);
            ImGui.textColored(
                    ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 0.55f,
                    NAV_SECTION_LABELS[s]);
            ImGui.dummy(0f, fontH * 0.15f);

            for (int p : NAV_SECTION_PANELS[s]) {
                if (p >= panels.size()) continue;
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
        }

        // ── Bottom hint: keyboard shortcut ────────────────────────────
        float footerH = ImGui.getFrameHeightWithSpacing() * 2.6f;
        float bottomY = ImGui.getWindowHeight() - footerH;
        if (bottomY > ImGui.getCursorPosY()) {
            ImGui.setCursorPosY(bottomY);
            GuiHelpers.subtleSeparator();
            ImGui.dummy(0f, fontH * 0.25f);
            ImGui.setCursorPosX(ImGui.getCursorPosX() + indent);
            ImGui.textColored(
                    ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 0.55f,
                    Icons.DIAGRAM + "  Blueprint");
            ImGui.sameLine(0, ImGui.getStyle().getItemSpacingX());
            GuiHelpers.kbdHint("F2");
        }
    }

    private static byte[] loadResourceFont(String resourcePath) {
        try (var in = ImGuiApp.class.getResourceAsStream(resourcePath)) {
            if (in != null) return in.readAllBytes();
        } catch (Exception ignored) {}
        return null;
    }

    private static byte[] loadSystemFont(String... candidates) {
        String windir = System.getenv("WINDIR");
        if (windir == null) windir = "C:\\Windows";
        java.nio.file.Path fontsDir = java.nio.file.Paths.get(windir, "Fonts");
        for (String name : candidates) {
            java.nio.file.Path p = fontsDir.resolve(name);
            if (java.nio.file.Files.exists(p)) {
                try { return java.nio.file.Files.readAllBytes(p); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private void updateTitle() {
        if (glfwWindow == 0) return;
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
                    if (c.getName().equals(name)) return c;
                }
                return null;
            });
        }
        if (ctx.getManagementRuntime() != null) {
            ctx.getManagementRuntime().stopAll();
        }
        ctx.disconnectAll();
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
