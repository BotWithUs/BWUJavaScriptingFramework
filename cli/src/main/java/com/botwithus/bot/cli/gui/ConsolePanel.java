package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.CommandParser;
import com.botwithus.bot.cli.command.CommandRegistry;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.command.impl.ExitCommand;
import com.botwithus.bot.cli.output.AnsiCodes;

import imgui.ImGui;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import org.lwjgl.glfw.GLFW;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Console/terminal panel — contains the output area and command input bar.
 * Extracted from ImGuiApp to serve as one tab in the tabbed GUI.
 */
public class ConsolePanel implements GuiPanel {

    private final AnsiOutputBuffer outputBuffer;
    private final CommandRegistry registry;
    private final ExecutorService executor;
    private final Runnable shutdownHook;

    // Input state
    private final ImString inputBuffer = new ImString(512);
    private final List<String> history = new ArrayList<>();
    private int historyIndex = -1;
    private boolean focusInput = true;
    private boolean scrollToBottom = true;
    private float copyFeedbackTimer = 0f;

    public ConsolePanel(AnsiOutputBuffer outputBuffer, CommandRegistry registry,
                        ExecutorService executor, Runnable shutdownHook) {
        this.outputBuffer = outputBuffer;
        this.registry = registry;
        this.executor = executor;
        this.shutdownHook = shutdownHook;
    }

    @Override
    public String title() {
        return "Console";
    }

    @Override
    public void render(CliContext ctx) {
        float inputBarHeight = ImGui.getFrameHeightWithSpacing() + 8f;
        float outputHeight = ImGui.getContentRegionAvailY() - inputBarHeight;

        renderOutput(outputHeight);
        renderInputBar(ctx);
    }

    private void renderOutput(float height) {
        // Copy button row above output
        copyFeedbackTimer = ClipboardHelper.renderCopyFeedback(copyFeedbackTimer);
        if (copyFeedbackTimer <= 0f && GuiHelpers.buttonSecondary(Icons.COPY + "  Copy Console")) {
            copyConsoleToClipboard(null);
        }

        ImGui.beginChild("output", 0, height - ImGui.getFrameHeightWithSpacing(), false, ImGuiWindowFlags.HorizontalScrollbar);

        List<OutputLine> snapshot = outputBuffer.snapshot();
        for (int i = 0; i < snapshot.size(); i++) {
            OutputLine line = snapshot.get(i);
            if (line.isRemoved()) continue;

            switch (line.getType()) {
                case TEXT -> {
                    renderTextLine(line);
                    if (ImGui.beginPopupContextItem("lineCtx_" + i)) {
                        if (ImGui.menuItem("Copy Line")) {
                            ClipboardHelper.copyToClipboard(extractLineText(line));
                        }
                        if (ImGui.menuItem("Copy All")) {
                            copyConsoleToClipboard(snapshot);
                        }
                        ImGui.endPopup();
                    }
                }
                case IMAGE -> renderImageLine(line);
                case PROGRESS -> renderProgressLine(line);
                case STREAM -> renderStreamLine(line);
            }
        }

        if (scrollToBottom && ImGui.getScrollY() >= ImGui.getScrollMaxY() - 10) {
            ImGui.setScrollHereY(1.0f);
        }

        ImGui.endChild();
    }

    private void renderTextLine(OutputLine line) {
        List<OutputLine.Segment> segments = line.getSegments();
        if (segments == null || segments.isEmpty()) {
            ImGui.text("");
            return;
        }

        boolean first = true;
        for (OutputLine.Segment seg : segments) {
            if (!first) {
                ImGui.sameLine(0, 0);
            }
            first = false;

            float r = seg.bold() ? Math.min(seg.r() + 0.1f, 1f) : seg.r();
            float g = seg.bold() ? Math.min(seg.g() + 0.1f, 1f) : seg.g();
            float b = seg.bold() ? Math.min(seg.b() + 0.1f, 1f) : seg.b();

            ImGui.textColored(r, g, b, seg.a(), seg.text());
        }
    }

    private void renderImageLine(OutputLine line) {
        int texId = line.getTextureId();
        if (texId > 0) {
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

    private void renderInputBar(CliContext ctx) {
        GuiHelpers.subtleSeparator();
        ImGui.spacing();

        // Prompt
        boolean connected = ctx.hasActiveConnection();
        String connName = ctx.getActiveConnectionName();
        int count = ctx.getConnections().size();
        String mountedName = ctx.getMountedConnectionName();

        if (connected && connName != null) {
            ImGui.textColored(ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.8f, ">");
            ImGui.sameLine(0, 6);
            GuiHelpers.textSecondary("bwu:");
            ImGui.sameLine(0, 0);
            ImGui.textColored(ImGuiTheme.CYAN_R, ImGuiTheme.CYAN_G, ImGuiTheme.CYAN_B, 0.9f, connName);
            if (count > 1) {
                ImGui.sameLine(0, 2);
                GuiHelpers.textMuted("[" + count + "]");
            }
            if (mountedName != null) {
                ImGui.sameLine(0, 6);
                ImGui.textColored(ImGuiTheme.MAGENTA_R, ImGuiTheme.MAGENTA_G, ImGuiTheme.MAGENTA_B, 0.7f, "[mounted]");
            }
            ImGui.sameLine(0, 2);
            GuiHelpers.textMuted(">");
        } else {
            ImGui.textColored(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 0.6f, "o");
            ImGui.sameLine(0, 6);
            GuiHelpers.textMuted("bwu>");
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
                handleCommand(text, ctx);
            }
            inputBuffer.set("");
            focusInput = true;
            scrollToBottom = true;
        }

        // Handle history with arrow keys
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

    private void handleCommand(String line, CliContext ctx) {
        PrintStream out = outputBuffer.getPrintStream();
        out.println(AnsiCodes.colorize("> " + line, "\u001B[33m"));

        executor.submit(() -> {
            ParsedCommand parsed = CommandParser.parse(line);
            Command cmd = registry.resolve(parsed.name());

            if (cmd == null) {
                out.println("Unknown command: " + parsed.name() + ". Type 'help' for available commands.");
                return;
            }

            if (cmd instanceof ExitCommand) {
                shutdownHook.run();
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
            inputBuffer.set(matches.getFirst());
        } else if (matches.size() > 1) {
            String common = matches.getFirst();
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

    private static String extractLineText(OutputLine line) {
        List<OutputLine.Segment> segments = line.getSegments();
        if (segments == null || segments.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (OutputLine.Segment seg : segments) {
            sb.append(seg.text());
        }
        return sb.toString();
    }

    private void copyConsoleToClipboard(List<OutputLine> existing) {
        List<OutputLine> lines = existing != null ? existing : outputBuffer.snapshot();
        StringBuilder sb = new StringBuilder();
        for (OutputLine line : lines) {
            if (line.isRemoved()) continue;
            if (line.getType() == OutputLine.Type.TEXT) {
                sb.append(extractLineText(line)).append('\n');
            } else if (line.getType() == OutputLine.Type.PROGRESS) {
                sb.append(line.getLabel() != null ? line.getLabel() : "").append('\n');
            }
        }
        ClipboardHelper.copyToClipboard(sb.toString());
        copyFeedbackTimer = ClipboardHelper.FEEDBACK_DURATION;
    }

    /** Clear the output buffer (used by clear command). */
    public void clearOutput() {
        outputBuffer.clear();
    }
}
