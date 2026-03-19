package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.log.LogEntry;

import imgui.ImGui;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Log viewer panel — shows log entries with level/source/connection filtering.
 */
public class LogsPanel implements GuiPanel {

    private static final String[] LEVELS = {"ALL", "INFO", "WARN", "ERROR"};
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final ImInt levelFilter = new ImInt(0);
    private final ImString sourceFilter = new ImString("", 128);
    private final ImInt lineCount = new ImInt(200);
    private final ImBoolean followMode = new ImBoolean(true);
    private final ImInt connectionFilter = new ImInt(0);
    private float copyFeedbackTimer = 0f;

    @Override
    public String title() {
        return "Logs";
    }

    @Override
    public void render(CliContext ctx) {
        // Filter controls row 1
        GuiHelpers.textSecondary("Level:");
        ImGui.sameLine();
        ImGui.pushItemWidth(80);
        ImGui.combo("##levelFilter", levelFilter, LEVELS);
        ImGui.popItemWidth();

        ImGui.sameLine(0, 16);
        GuiHelpers.textSecondary("Source:");
        ImGui.sameLine();
        ImGui.pushItemWidth(120);
        ImGui.inputText("##sourceFilter", sourceFilter);
        ImGui.popItemWidth();

        // Connection filter
        ImGui.sameLine(0, 16);
        GuiHelpers.textSecondary("Connection:");
        ImGui.sameLine();
        var connections = new ArrayList<>(ctx.getConnections());
        String[] connOptions = new String[connections.size() + 1];
        connOptions[0] = "All";
        for (int i = 0; i < connections.size(); i++) {
            connOptions[i + 1] = connections.get(i).getName();
        }
        ImGui.pushItemWidth(120);
        if (connectionFilter.get() >= connOptions.length) connectionFilter.set(0);
        ImGui.combo("##connFilter", connectionFilter, connOptions);
        ImGui.popItemWidth();

        ImGui.sameLine(0, 16);
        ImGui.checkbox("Follow", followMode);

        ImGui.sameLine(0, 16);
        GuiHelpers.textSecondary("Lines:");
        ImGui.sameLine();
        ImGui.pushItemWidth(80);
        int[] lineCountArr = {lineCount.get()};
        if (ImGui.sliderInt("##lineCount", lineCountArr, 50, 1000)) {
            lineCount.set(lineCountArr[0]);
        }
        ImGui.popItemWidth();

        ImGui.sameLine(0, 12);
        if (GuiHelpers.buttonDanger(Icons.TRASH + "  Clear")) {
            ctx.getLogBuffer().clear();
        }

        ImGui.sameLine(0, 8);
        boolean wantCopy = false;
        copyFeedbackTimer = ClipboardHelper.renderCopyFeedback(copyFeedbackTimer);
        if (copyFeedbackTimer <= 0f && GuiHelpers.buttonSecondary(Icons.COPY + "  Copy Logs")) {
            wantCopy = true;
        }

        ImGui.spacing();

        // Get log entries
        List<LogEntry> entries = ctx.getLogBuffer().tail(lineCount.get());
        List<LogEntry> filtered = filterEntries(entries, connections);

        if (wantCopy) {
            copyLogsToClipboard(filtered);
        }

        // Log table
        float tableHeight = ImGui.getContentRegionAvailY();
        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp
                | ImGuiTableFlags.ScrollY;
        if (ImGui.beginTable("logsTable", 5, flags, 0, tableHeight)) {
            ImGui.tableSetupColumn("Time", 0, 0.8f);
            ImGui.tableSetupColumn("Level", 0, 0.4f);
            ImGui.tableSetupColumn("Source", 0, 0.5f);
            ImGui.tableSetupColumn("Connection", 0, 0.6f);
            ImGui.tableSetupColumn("Message", 0, 3.0f);
            ImGui.tableHeadersRow();

            for (int i = 0; i < filtered.size(); i++) {
                LogEntry entry = filtered.get(i);
                ImGui.tableNextRow();

                ImGui.tableSetColumnIndex(0);
                ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f,
                        TIME_FMT.format(entry.timestamp()));

                ImGui.tableSetColumnIndex(1);
                renderLevel(entry.level());

                ImGui.tableSetColumnIndex(2);
                ImGui.text(entry.source() != null ? entry.source() : "-");

                ImGui.tableSetColumnIndex(3);
                ImGui.text(entry.connection() != null ? entry.connection() : "-");

                ImGui.tableSetColumnIndex(4);
                if ("ERROR".equals(entry.level())) {
                    ImGui.textColored(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 1f,
                            entry.message() != null ? entry.message() : "");
                } else {
                    ImGui.text(entry.message() != null ? entry.message() : "");
                }

                // Right-click context menu for this row
                if (ImGui.beginPopupContextItem("logRowCtx_" + i)) {
                    if (ImGui.menuItem("Copy Message")) {
                        ClipboardHelper.copyToClipboard(entry.message() != null ? entry.message() : "");
                    }
                    if (ImGui.menuItem("Copy Row")) {
                        ClipboardHelper.copyToClipboard(formatLogEntry(entry));
                    }
                    if (ImGui.menuItem("Copy All Logs")) {
                        copyLogsToClipboard(filtered);
                    }
                    ImGui.endPopup();
                }
            }

            if (followMode.get()) {
                ImGui.setScrollHereY(1.0f);
            }

            ImGui.endTable();
        }
    }

    private List<LogEntry> filterEntries(List<LogEntry> entries, List<Connection> connections) {
        String levelStr = LEVELS[levelFilter.get()];
        String source = sourceFilter.get().trim();
        int connIdx = connectionFilter.get();
        String connName = connIdx > 0 && connIdx <= connections.size()
                ? connections.get(connIdx - 1).getName() : null;

        List<LogEntry> result = new ArrayList<>();
        for (LogEntry entry : entries) {
            // Level filter
            if (!"ALL".equals(levelStr) && !levelStr.equals(entry.level())) {
                continue;
            }
            // Source filter
            if (!source.isEmpty() && (entry.source() == null || !entry.source().contains(source))) {
                continue;
            }
            // Connection filter
            if (connName != null && !connName.equals(entry.connection())) {
                continue;
            }
            result.add(entry);
        }
        return result;
    }

    private static String formatLogEntry(LogEntry entry) {
        return TIME_FMT.format(entry.timestamp()) + "\t" +
                (entry.level() != null ? entry.level() : "-") + "\t" +
                (entry.source() != null ? entry.source() : "-") + "\t" +
                (entry.connection() != null ? entry.connection() : "-") + "\t" +
                (entry.message() != null ? entry.message() : "");
    }

    private void copyLogsToClipboard(List<LogEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (LogEntry entry : entries) {
            sb.append(formatLogEntry(entry)).append('\n');
        }
        ClipboardHelper.copyToClipboard(sb.toString());
        copyFeedbackTimer = ClipboardHelper.FEEDBACK_DURATION;
    }

    private void renderLevel(String level) {
        if (level == null) {
            ImGui.text("-");
            return;
        }
        switch (level) {
            case "ERROR" -> ImGui.textColored(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 1f, level);
            case "WARN" -> ImGui.textColored(ImGuiTheme.YELLOW_R, ImGuiTheme.YELLOW_G, ImGuiTheme.YELLOW_B, 1f, level);
            case "INFO" -> ImGui.textColored(ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B, 1f, level);
            default -> ImGui.text(level);
        }
    }
}
