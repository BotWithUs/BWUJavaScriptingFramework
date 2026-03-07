package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.blueprint.BlueprintEditor;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.CommandParser;
import com.botwithus.bot.cli.command.CommandRegistry;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.command.impl.*;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.cli.output.AnsiCodes;
import com.botwithus.bot.cli.stream.StreamManager;

import imgui.ImFont;
import imgui.ImFontConfig;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImVec2;
import imgui.app.Application;
import imgui.app.Configuration;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import org.lwjgl.glfw.GLFW;

import java.awt.image.BufferedImage;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main imgui-based application replacing the Swing GUI.
 * Extends {@code imgui.app.Application} for window management and GL context.
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

    // Input state
    private final ImString inputBuffer = new ImString(512);
    private final List<String> history = new ArrayList<>();
    private int historyIndex = -1;
    private boolean focusInput = true;
    private boolean scrollToBottom = true;

    // Blueprint editor mode
    private boolean editorMode = false;
    private BlueprintEditor blueprintEditor;

    // Script config panel
    private ScriptConfigPanel configPanel;

    // GLFW window handle for title updates
    private long glfwWindow;

    @Override
    protected void configure(Configuration config) {
        config.setTitle("JBot — disconnected");
        config.setWidth(900);
        config.setHeight(650);
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

        // Rebuild font atlas at scaled pixel size so text is crisp on HiDPI.
        // super.initImGui already added a default font — clear it and rebuild.
        ImGuiIO io = ImGui.getIO();
        io.getFonts().clear();
        ImFontConfig fontConfig = new ImFontConfig();
        fontConfig.setSizePixels(14f * dpiScale);
        fontConfig.setOversampleH(2);
        fontConfig.setOversampleV(2);
        io.getFonts().addFontDefault(fontConfig);
        io.getFonts().build();
        fontConfig.destroy();

        ImGuiTheme.apply(dpiScale);

        textureManager = new TextureManager();
        outputBuffer = new AnsiOutputBuffer();

        PrintStream guiOut = outputBuffer.getPrintStream();
        PrintStream guiErr = outputBuffer.getPrintStream();

        LogBuffer logBuffer = new LogBuffer();
        LogCapture logCapture = new LogCapture(logBuffer, guiOut, guiErr);
        logCapture.install();

        ctx = new CliContext(logBuffer, logCapture);
        ctx.setStreamManager(new StreamManager(outputBuffer, textureManager, guiOut));

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
        registry.register(new ClearCommand());
        registry.register(new ExitCommand());

        // Image display hook — queues texture creation on GL thread, then appends to buffer
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

        // Initialize blueprint editor
        blueprintEditor = new BlueprintEditor();

        // Initialize config panel and wire opener
        configPanel = new ScriptConfigPanel();
        ctx.setConfigPanelOpener(runner -> configPanel.open(runner));

        // Grab GLFW window handle for title updates
        glfwWindow = GLFW.glfwGetCurrentContext();
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

        // Full-window imgui window
        ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
        ImGui.setNextWindowSize(ImGui.getIO().getDisplaySizeX(), ImGui.getIO().getDisplaySizeY(), ImGuiCond.Always);

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
            float inputBarHeight = ImGui.getFrameHeightWithSpacing() + 8f;
            float outputHeight = ImGui.getContentRegionAvailY() - inputBarHeight;

            renderOutput(outputHeight);
            renderInputBar();
        }

        ImGui.end();

        // Render config panel as floating window (outside the main window)
        if (configPanel != null && configPanel.isOpen()) {
            configPanel.render();
        }

        // Update window title based on connection state
        updateTitle();
    }

    private void renderOutput(float height) {
        ImGui.beginChild("output", 0, height, false, ImGuiWindowFlags.HorizontalScrollbar);

        List<OutputLine> snapshot = outputBuffer.snapshot();
        for (OutputLine line : snapshot) {
            if (line.isRemoved()) continue;

            switch (line.getType()) {
                case TEXT -> renderTextLine(line);
                case IMAGE -> renderImageLine(line);
                case PROGRESS -> renderProgressLine(line);
                case STREAM -> renderStreamLine(line);
            }
        }

        // Auto-scroll if at bottom
        if (scrollToBottom && ImGui.getScrollY() >= ImGui.getScrollMaxY() - 10) {
            ImGui.setScrollHereY(1.0f);
        }

        ImGui.endChild();
    }

    private void renderTextLine(OutputLine line) {
        List<OutputLine.Segment> segments = line.getSegments();
        if (segments == null || segments.isEmpty()) {
            ImGui.text(""); // blank line
            return;
        }

        boolean first = true;
        for (OutputLine.Segment seg : segments) {
            if (!first) {
                ImGui.sameLine(0, 0);
            }
            first = false;

            // Bold is simulated by slightly brighter color (imgui doesn't have font weight per-char easily)
            float r = seg.bold() ? Math.min(seg.r() + 0.1f, 1f) : seg.r();
            float g = seg.bold() ? Math.min(seg.g() + 0.1f, 1f) : seg.g();
            float b = seg.bold() ? Math.min(seg.b() + 0.1f, 1f) : seg.b();

            ImGui.textColored(r, g, b, seg.a(), seg.text());
        }
    }

    private void renderImageLine(OutputLine line) {
        int texId = line.getTextureId();
        if (texId > 0) {
            // Clamp display size
            float maxW = Math.min(line.getImageWidth(), ImGui.getContentRegionAvailX());
            float scale = maxW / line.getImageWidth();
            float displayH = line.getImageHeight() * scale;
            ImGui.image(texId, maxW, displayH);
        }
    }

    private void renderProgressLine(OutputLine line) {
        String label = line.getLabel() != null ? line.getLabel() : "Working...";
        float progress = line.getProgress();
        if (progress < 0) {
            // Indeterminate — use animated fraction based on time
            float t = (float) (ImGui.getTime() % 2.0) / 2.0f;
            ImGui.progressBar(t, 250, 16, label);
        } else {
            ImGui.progressBar(progress, 250, 16, label);
        }
    }

    private void renderStreamLine(OutputLine line) {
        String label = line.getLabel();
        if (label != null) {
            ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f,
                    "  " + label);
        }
        int texId = line.getTextureId();
        if (texId > 0) {
            float maxW = Math.min(line.getImageWidth(), ImGui.getContentRegionAvailX());
            float scale = maxW / line.getImageWidth();
            float displayH = line.getImageHeight() * scale;
            ImGui.image(texId, maxW, displayH);
        }
    }

    private void renderInputBar() {
        ImGui.separator();

        // Prompt
        boolean connected = ctx.hasActiveConnection();
        String connName = ctx.getActiveConnectionName();
        int count = ctx.getConnections().size();
        String mountedName = ctx.getMountedConnectionName();

        if (connected && connName != null) {
            ImGui.textColored(ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B, 1f, "*");
            ImGui.sameLine(0, 4);
            ImGui.text("jbot:");
            ImGui.sameLine(0, 0);
            ImGui.textColored(ImGuiTheme.CYAN_R, ImGuiTheme.CYAN_G, ImGuiTheme.CYAN_B, 1f, connName);
            if (count > 1) {
                ImGui.sameLine(0, 0);
                ImGui.text(" [" + count + "]");
            }
            if (mountedName != null) {
                ImGui.sameLine(0, 4);
                ImGui.textColored(ImGuiTheme.MAGENTA_R, ImGuiTheme.MAGENTA_G, ImGuiTheme.MAGENTA_B, 1f, "[mounted]");
            }
            ImGui.sameLine(0, 0);
            ImGui.text("> ");
        } else {
            ImGui.textColored(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 1f, "o");
            ImGui.sameLine(0, 4);
            ImGui.text("jbot> ");
        }

        ImGui.sameLine();

        // Input field
        ImGui.pushItemWidth(ImGui.getContentRegionAvailX());
        int flags = ImGuiInputTextFlags.EnterReturnsTrue | ImGuiInputTextFlags.CallbackHistory
                | ImGuiInputTextFlags.CallbackCompletion;

        if (focusInput) {
            ImGui.setKeyboardFocusHere();
            focusInput = false;
        }

        if (ImGui.inputText("##input", inputBuffer, flags)) {
            String text = inputBuffer.get().trim();
            if (!text.isEmpty()) {
                history.add(text);
                historyIndex = history.size();
                handleCommand(text);
            }
            inputBuffer.set("");
            focusInput = true;
            scrollToBottom = true;
        }

        // Handle history callback via imgui's built-in mechanism
        // For now, handle arrow keys manually since inputText callbacks are complex in imgui-java
        if (ImGui.isItemFocused()) {
            if (ImGui.isKeyPressed(GLFW.GLFW_KEY_UP)) {
                if (!history.isEmpty() && historyIndex > 0) {
                    historyIndex--;
                    inputBuffer.set(history.get(historyIndex));
                }
            }
            if (ImGui.isKeyPressed(GLFW.GLFW_KEY_DOWN)) {
                if (historyIndex < history.size()) {
                    historyIndex++;
                    if (historyIndex == history.size()) {
                        inputBuffer.set("");
                    } else {
                        inputBuffer.set(history.get(historyIndex));
                    }
                }
            }
            if (ImGui.isKeyPressed(GLFW.GLFW_KEY_TAB)) {
                autoComplete();
            }
        }

        ImGui.popItemWidth();
    }

    private void handleCommand(String line) {
        // Echo input in accent color
        PrintStream out = outputBuffer.getPrintStream();
        out.println(AnsiCodes.colorize("> " + line, "\u001B[33m")); // yellow-ish echo

        executor.submit(() -> {
            ParsedCommand parsed = CommandParser.parse(line);
            Command cmd = registry.resolve(parsed.name());

            if (cmd == null) {
                out.println("Unknown command: " + parsed.name() + ". Type 'help' for available commands.");
                return;
            }

            if (cmd instanceof ExitCommand) {
                shutdown();
                return;
            }

            try {
                cmd.execute(parsed, ctx);
            } catch (com.botwithus.bot.core.pipe.PipeException | com.botwithus.bot.core.rpc.RpcException e) {
                out.println("Connection error: " + e.getMessage());
                String connName = ctx.getActiveConnectionName();
                if (connName != null) {
                    ctx.handleConnectionError(connName);
                }
            } catch (Exception e) {
                out.println("Error: " + e.getMessage());
            }
        });
    }

    private void autoComplete() {
        if (registry == null) return;
        String prefix = inputBuffer.get().toLowerCase();
        if (prefix.isEmpty()) return;

        List<String> matches = new ArrayList<>();
        for (var cmd : registry.all()) {
            if (cmd.name().toLowerCase().startsWith(prefix)) {
                matches.add(cmd.name());
            }
            for (String alias : cmd.aliases()) {
                if (alias.toLowerCase().startsWith(prefix)) {
                    matches.add(alias);
                }
            }
        }

        if (matches.size() == 1) {
            inputBuffer.set(matches.get(0));
        } else if (matches.size() > 1) {
            String common = matches.get(0);
            for (int i = 1; i < matches.size(); i++) {
                common = commonPrefix(common, matches.get(i));
            }
            if (common.length() > prefix.length()) {
                inputBuffer.set(common);
            }
        }
    }

    private static String commonPrefix(String a, String b) {
        int len = Math.min(a.length(), b.length());
        int i = 0;
        while (i < len && Character.toLowerCase(a.charAt(i)) == Character.toLowerCase(b.charAt(i))) {
            i++;
        }
        return a.substring(0, i);
    }

    private void updateTitle() {
        if (glfwWindow == 0) return;
        boolean connected = ctx.hasActiveConnection();
        String connName = ctx.getActiveConnectionName();
        int count = ctx.getConnections().size();

        String title;
        if (connected && connName != null) {
            String suffix = count > 1 ? " [" + count + "]" : "";
            title = "JBot — " + connName + suffix;
        } else {
            title = "JBot — disconnected";
        }
        GLFW.glfwSetWindowTitle(glfwWindow, title);
    }

    private void shutdown() {
        if (ctx.getStreamManager() != null) {
            ctx.getStreamManager().stopAll(name -> {
                for (var c : ctx.getConnections()) {
                    if (c.getName().equals(name)) return c;
                }
                return null;
            });
        }
        ctx.disconnectAll();
        executor.shutdownNow();
        System.exit(0);
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
