package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.AutoStartManager;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.blueprint.BlueprintEditor;
import com.botwithus.bot.cli.command.CommandRegistry;
import com.botwithus.bot.cli.command.impl.*;
import com.botwithus.bot.cli.log.LogBuffer;
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
import imgui.flag.ImGuiTabBarFlags;
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

               _ ____        _      ____ _     ___
              | | __ )  ___ | |_   / ___| |   |_ _|
           _  | |  _ \\ / _ \\| __| | |   | |    | |
          | |_| | |_) | (_) | |_  | |___| |___ | |
           \\___/|____/ \\___/ \\__|  \\____|_____|___|
                  BotWithUs Script Manager

              Type 'help' for available commands.
              Press F2 to open the Blueprint Editor.
            """;

    private TextureManager textureManager;
    private AnsiOutputBuffer outputBuffer;
    private CliContext ctx;
    private CommandRegistry registry;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jbot-cmd");
        t.setDaemon(true);
        return t;
    });

    // Panels
    private final List<GuiPanel> panels = new ArrayList<>();
    private StatusBar statusBar;

    // Blueprint editor mode
    private boolean editorMode = false;
    private BlueprintEditor blueprintEditor;

    // Script custom UI window (floating window)
    private ScriptUIWindow scriptUIWindow;

    // GLFW window handle for title updates
    private long glfwWindow;

    @Override
    protected void configure(Configuration config) {
        config.setTitle("JBot \u2014 disconnected");
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
        float dpiScale = Math.max(xScale[0], 1.0f);

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

        // Initialize panels
        panels.add(new ConsolePanel(outputBuffer, registry, executor, this::shutdown));
        panels.add(new ConnectionsPanel(executor, registry));
        panels.add(new ScriptsPanel(executor));
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

            // Tab bar
            if (ImGui.beginTabBar("##mainTabs", ImGuiTabBarFlags.None)) {
                for (GuiPanel panel : panels) {
                    if (ImGui.beginTabItem(panel.title())) {
                        // Panel content area — fill available space minus status bar
                        float panelHeight = ImGui.getContentRegionAvailY() - statusBarHeight;
                        ImGui.beginChild("##tabContent", 0, panelHeight, false);
                        panel.render(ctx);
                        ImGui.endChild();
                        ImGui.endTabItem();
                    }
                }
                ImGui.endTabBar();
            }

            // Status bar at the bottom
            statusBar.render(ctx);
        }

        ImGui.end();

        // Render script custom UI as a floating window (outside the main window)
        if (scriptUIWindow != null && scriptUIWindow.isOpen()) {
            scriptUIWindow.render();
        }

        // Update window title based on connection state
        updateTitle();
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
            title = "JBot \u2014 " + connName + suffix;
        } else {
            title = "JBot \u2014 disconnected";
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
