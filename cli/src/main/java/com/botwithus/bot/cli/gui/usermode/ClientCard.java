package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.gui.CategoryStyle;
import com.botwithus.bot.cli.gui.GuiHelpers;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.core.runtime.ScriptRunner;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;

import java.util.List;

/**
 * Renders a single client connection card in User Mode.
 * Three visual states: script running, idle, disconnected.
 */
public class ClientCard {

    /**
     * Render a client card. Returns a {@link CardAction} if the user triggered one, or null.
     */
    public CardAction render(Connection connection, float cardWidth, int cardIndex) {
        CardAction action = null;
        boolean alive = connection.isAlive();

        // Push reduced opacity for disconnected clients
        if (!alive) {
            ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.Alpha, 0.55f);
        }

        ImGui.pushStyleColor(ImGuiCol.ChildBg,
                ImGuiTheme.SURFACE_R, ImGuiTheme.SURFACE_G, ImGuiTheme.SURFACE_B, 1f);
        ImGui.pushStyleColor(ImGuiCol.Border,
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.4f);
        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.ChildRounding, 8f);

        // Dynamic height — calculate based on content
        float cardHeight = estimateCardHeight(connection, alive);

        ImGui.beginChild("##clientCard" + cardIndex, cardWidth, cardHeight, true);
        ImGui.popStyleColor(2);
        ImGui.popStyleVar(); // ChildRounding

        // --- Header: Status dot + Connection name ---
        renderHeader(connection, alive);

        ImGui.spacing();
        GuiHelpers.subtleSeparator();
        ImGui.spacing();

        // --- Script status section ---
        if (!alive) {
            // Disconnected state
            GuiHelpers.textMuted(Icons.CIRCLE + "  Disconnected");
            ImGui.spacing();
            ImGui.spacing();
            if (GuiHelpers.buttonSecondary(Icons.PLUG + "  Reconnect##" + cardIndex)) {
                action = new CardAction(CardAction.Type.RECONNECT, connection, null);
            }
        } else {
            List<ScriptRunner> runners = connection.getRuntime().getRunners();
            ScriptRunner activeRunner = runners.stream()
                    .filter(ScriptRunner::isRunning)
                    .findFirst().orElse(null);

            if (activeRunner != null) {
                // Running state
                renderRunningScript(activeRunner, cardIndex);
                ImGui.spacing();

                // Action buttons
                boolean hasUI = activeRunner.getScript().getUI() != null
                        || (activeRunner.getConfigFields() != null && !activeRunner.getConfigFields().isEmpty());
                if (hasUI) {
                    if (GuiHelpers.buttonSecondary(Icons.GEAR + "  Configure##" + cardIndex)) {
                        action = new CardAction(CardAction.Type.CONFIGURE, connection, activeRunner);
                    }
                    ImGui.sameLine(0, 8);
                }
                if (GuiHelpers.buttonDanger(Icons.STOP + "  Stop##" + cardIndex)) {
                    action = new CardAction(CardAction.Type.STOP, connection, activeRunner);
                }
            } else {
                // Idle state
                ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 0.8f,
                        Icons.STOP + "  No script running");
                ImGui.spacing();
                ImGui.spacing();
                if (GuiHelpers.buttonPrimary(Icons.PLAY + "  Start Script" + "##" + cardIndex)) {
                    action = new CardAction(CardAction.Type.START_SCRIPT, connection, null);
                }
            }
        }

        ImGui.endChild();

        if (!alive) {
            ImGui.popStyleVar(); // Alpha
        }

        return action;
    }

    private void renderHeader(Connection connection, boolean alive) {
        // Status dot
        if (alive) {
            // Pulsing green dot
            float pulse = 0.75f + 0.25f * (float) Math.sin(ImGui.getTime() * 2.0);
            GuiHelpers.statusDot(
                    ImGuiTheme.GREEN_R * pulse,
                    ImGuiTheme.GREEN_G * pulse,
                    ImGuiTheme.GREEN_B * pulse);
        } else {
            GuiHelpers.statusDot(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B);
        }
        ImGui.sameLine(0, 6);

        // Connection name (primary identifier)
        String displayName = connection.getAccountName();
        if (displayName == null || displayName.isEmpty()) {
            displayName = connection.getName();
        }
        ImGui.text(displayName);

        // Subtitle: pipe name if we have an account name
        String accountName = connection.getAccountName();
        if (accountName != null && !accountName.isEmpty()) {
            ImGui.sameLine(0, 12);
            GuiHelpers.textMuted(connection.getName());
        }
    }

    private void renderRunningScript(ScriptRunner runner, int cardIndex) {
        ScriptManifest manifest = runner.getManifest();
        String scriptName = runner.getScriptName();

        // Script icon + name
        if (manifest != null) {
            CategoryStyle.Style catStyle = CategoryStyle.of(manifest.category());
            ImGui.textColored(catStyle.r(), catStyle.g(), catStyle.b(), 1f, catStyle.icon());
            ImGui.sameLine(0, 6);
        } else {
            ImGui.textColored(ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 1f,
                    Icons.PLAY);
            ImGui.sameLine(0, 6);
        }

        ImGui.text(scriptName);

        // Performance info from profiler
        long loops = runner.getProfiler().getLoopCount();
        long avgMs = loops > 0
                ? runner.getProfiler().getTotalLoopTimeNanos() / loops / 1_000_000L
                : 0;
        if (avgMs > 0) {
            ImGui.sameLine(0, 12);
            GuiHelpers.statusBadge(avgMs + "ms",
                    ImGuiTheme.BLUE_ACCENT_R, ImGuiTheme.BLUE_ACCENT_G, ImGuiTheme.BLUE_ACCENT_B);
        }

        // Author + version subtitle
        if (manifest != null) {
            StringBuilder sub = new StringBuilder();
            if (!manifest.author().isEmpty()) {
                sub.append("by ").append(manifest.author());
            }
            if (!manifest.version().isEmpty()) {
                if (!sub.isEmpty()) sub.append(" \u00B7 ");
                sub.append("v").append(manifest.version());
            }
            if (!sub.isEmpty()) {
                GuiHelpers.textMuted(sub.toString());
            }
        }
    }

    private float estimateCardHeight(Connection connection, boolean alive) {
        float lineHeight = ImGui.getTextLineHeightWithSpacing();
        float padding = ImGui.getStyle().getWindowPaddingY() * 2;
        float separator = 8f;

        // Header (1 line) + separator + content
        float base = lineHeight + separator + padding + 10f;

        if (!alive) {
            // Disconnected: 1 line text + button
            return base + lineHeight + ImGui.getFrameHeightWithSpacing() + 8f;
        }

        List<ScriptRunner> runners = connection.getRuntime().getRunners();
        boolean hasRunning = runners.stream().anyMatch(ScriptRunner::isRunning);

        if (hasRunning) {
            // Running: script name line + subtitle + buttons
            return base + lineHeight * 2.5f + ImGui.getFrameHeightWithSpacing() + 12f;
        } else {
            // Idle: status text + button
            return base + lineHeight + ImGui.getFrameHeightWithSpacing() + 8f;
        }
    }

    /**
     * Represents a user action on a client card.
     */
    public record CardAction(Type type, Connection connection, ScriptRunner runner) {
        public enum Type {
            START_SCRIPT,
            STOP,
            CONFIGURE,
            RECONNECT
        }
    }
}
