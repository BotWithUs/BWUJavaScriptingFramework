package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.AutoStartManager;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.blueprint.BlueprintEditor;
import com.botwithus.bot.cli.command.CommandRegistry;
import com.botwithus.bot.cli.command.impl.*;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogBufferAppender;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.cli.output.AnsiCodes;
import com.botwithus.bot.cli.stream.StreamManager;
import com.botwithus.bot.core.config.ScriptProfileStore;

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

        float uiSize = (float) Math.round(19f * dpiScale);
        ImFontAtlas atlas = ImGui.getIO().getFonts();
        atlas.clear();
        byte[] ttf = loadSystemFont("segoeui.ttf", "arial.ttf", "verdana.ttf");
        ImFontConfig cfg = new ImFontConfig();
        cfg.setOversampleH(3);
        cfg.setOversampleV(3);
        cfg.setPixelSnapH(true);
        if (ttf != null) {
            atlas.addFontFromMemoryTTF(ttf, uiSize, cfg);
        } else {
            cfg.setSizePixels(uiSize);
            atlas.addFontDefault(cfg);
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

        // Initialize management script runtime
        ctx.initManagementRuntime();

        // Start auto-connect scanning if enabled
        autoStartManager.start();

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

        // Toggle editor mode with F2
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_F2)) {
            editorMode = !editorMode;
            if (!editorMode && blueprintEditor != null) {
                blueprintEditor.dispose();
            }
        }

        // Full-window imgui window — use main viewport pos for correct placement with viewports enabled
        var viewport = ImGui.getMainViewport();
        ImGui.setNextWindowPos(viewport.getPosX(), viewport.getPosY(), ImGuiCond.Always);
        ImGui.setNextWindowSize(viewport.getSizeX(), viewport.getSizeY(), ImGuiCond.Always);

        int windowFlags = ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoBringToFrontOnFocus;

        if (editorMode) {
            windowFlags |= ImGuiWindowFlags.MenuBar;
        }

        ImGui.begin("##main", windowFlags);

        if (editorMode) {
            try {
                blueprintEditor.render();
            } catch (Exception e) {
                editorMode = false;
                outputBuffer.getPrintStream().println("Blueprint editor error: " + e.getMessage());
                e.printStackTrace();
                blueprintEditor.dispose();
            }
        } else {
            // Reserve space for status bar at the bottom
            float statusBarHeight = ImGui.getFrameHeightWithSpacing() + 4f;
            float sidebarWidth = ImGui.calcTextSize("  EXTENSIONS  ").x + ImGui.getStyle().getWindowPaddingX() * 2;
            float contentHeight = ImGui.getContentRegionAvailY() - statusBarHeight;

            // --- Sidebar Navigation ---
            ImGui.pushStyleColor(ImGuiCol.ChildBg,
                    ImGuiTheme.SIDEBAR_BG_R, ImGuiTheme.SIDEBAR_BG_G, ImGuiTheme.SIDEBAR_BG_B, 1f);
            ImGui.beginChild("##sidebar", sidebarWidth, contentHeight, true);
            ImGui.popStyleColor();
            renderSidebar();
            ImGui.endChild();

            ImGui.sameLine(0, 0);

            // --- Content Area ---
            ImGui.beginChild("##content", 0, contentHeight, false);
            if (selectedPanel >= 0 && selectedPanel < panels.size()) {
                panels.get(selectedPanel).render(ctx);
            }
            ImGui.endChild();

            // Status bar at the bottom
            statusBar.render(ctx);
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

    // Sidebar navigation section definitions
    private static final String[] NAV_SECTION_LABELS = {"CORE", "EXTENSIONS", "SYSTEM"};
    private static final int[][] NAV_SECTION_PANELS = {
        {0, 1, 2},      // Console, Connections, Scripts
        {3, 4, 5},      // Management, Script UI, Groups
        {6, 7}           // Logs, Settings
    };

    private void renderSidebar() {
        // Brand header
        ImGui.spacing();
        ImGui.pushStyleColor(ImGuiCol.Text,
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 1f);
        ImGui.text("  BotWithUs");
        ImGui.popStyleColor();
        ImGui.spacing();
        ImGui.separator();

        int accentCol = ImGuiTheme.imCol32(
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 1f);

        for (int s = 0; s < NAV_SECTION_LABELS.length; s++) {
            ImGui.spacing();
            ImGui.textColored(
                    ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 0.6f,
                    "  " + NAV_SECTION_LABELS[s]);
            ImGui.spacing();

            for (int p : NAV_SECTION_PANELS[s]) {
                if (p >= panels.size()) continue;
                boolean isActive = (p == selectedPanel);

                if (isActive) {
                    ImGui.pushStyleColor(ImGuiCol.Header,
                            ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.15f);
                    ImGui.pushStyleColor(ImGuiCol.HeaderHovered,
                            ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.25f);
                    ImGui.pushStyleColor(ImGuiCol.HeaderActive,
                            ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.3f);
                }

                if (ImGui.selectable("   " + panels.get(p).title() + "##nav" + p, isActive)) {
                    selectedPanel = p;
                }

                if (isActive) {
                    // Draw accent indicator bar on left edge of active item
                    var drawList = ImGui.getWindowDrawList();
                    drawList.addRectFilled(
                            ImGui.getItemRectMinX(), ImGui.getItemRectMinY(),
                            ImGui.getItemRectMinX() + ImGui.getStyle().getFrameRounding() + 1f, ImGui.getItemRectMaxY(),
                            accentCol);
                    ImGui.popStyleColor(3);
                }
            }
        }

        // Bottom hint — pushed to bottom of sidebar
        float bottomY = ImGui.getWindowHeight() - ImGui.getFrameHeightWithSpacing() * 2;
        if (bottomY > ImGui.getCursorPosY()) {
            ImGui.setCursorPosY(bottomY);
            ImGui.separator();
            ImGui.textColored(
                    ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 0.5f,
                    "  F2: Blueprint Editor");
        }
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
