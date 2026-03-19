package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.CommandRegistry;
import com.botwithus.bot.cli.command.CommandResult;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.command.impl.ConnectCommand;

import imgui.ImGui;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImString;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Connections management panel — scan for pipes, connect/disconnect, switch active, mount/unmount.
 */
public class ConnectionsPanel implements GuiPanel {

    private final ExecutorService executor;
    private final CommandRegistry registry;
    private final ImString scanFilter = new ImString("BotWithUs", 128);

    private List<ConnectCommand.PipeInfo> scanResults;
    private volatile boolean scanning = false;
    private volatile String scanStatus;

    public ConnectionsPanel(ExecutorService executor, CommandRegistry registry) {
        this.executor = executor;
        this.registry = registry;
    }

    @Override
    public String title() {
        return "Connections";
    }

    @Override
    public void render(CliContext ctx) {
        // Scan controls
        GuiHelpers.textSecondary("Pipe Filter:");
        ImGui.sameLine();
        ImGui.pushItemWidth(200);
        ImGui.inputText("##scanFilter", scanFilter);
        ImGui.popItemWidth();
        ImGui.sameLine(0, 8);

        if (scanning) {
            ImGui.beginDisabled();
            GuiHelpers.buttonSecondary("Scanning...");
            ImGui.endDisabled();
        } else {
            if (GuiHelpers.buttonPrimary(Icons.SEARCH + "  Scan")) {
                startScan(ctx);
            }
        }

        ImGui.sameLine(0, 8);
        if (GuiHelpers.buttonSecondary(Icons.BOLT + "  Quick Connect")) {
            executor.submit(() -> {
                Command cmd = registry.resolve("connect");
                if (cmd != null) {
                    cmd.execute(new ParsedCommand("connect", List.of(), Map.of()), ctx);
                }
            });
        }

        if (scanStatus != null) {
            ImGui.sameLine(0, 12);
            GuiHelpers.textMuted(scanStatus);
        }

        // Scan results
        if (scanResults != null && !scanResults.isEmpty()) {
            GuiHelpers.sectionHeader("Available Pipes");
            renderScanTable(ctx);
        }

        // Active connections
        GuiHelpers.sectionHeader("Active Connections");
        renderConnectionsTable(ctx);
    }

    private void startScan(CliContext ctx) {
        scanning = true;
        scanStatus = "Scanning...";
        Thread.ofVirtual().name("pipe-scan").start(() -> {
            try {
                String filter = scanFilter.get().trim();
                if (filter.isEmpty()) filter = "BotWithUs";

                Command cmd = registry.resolve("connect");
                if (cmd == null) {
                    scanStatus = "Connect command not found.";
                    return;
                }

                CommandResult result = cmd.executeWithResult(
                        new ParsedCommand("connect", List.of("scan", filter), Map.of()), ctx);

                List<ConnectCommand.PipeInfo> infos = result.get("scanResults");
                if (infos != null) {
                    scanResults = infos;
                    scanStatus = result.message();
                } else {
                    scanResults = List.of();
                    scanStatus = result.message() != null ? result.message() : "No pipes found.";
                }
            } catch (Exception e) {
                scanStatus = "Scan error: " + e.getMessage();
            } finally {
                scanning = false;
            }
        });
    }

    private void renderScanTable(CliContext ctx) {
        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
        if (ImGui.beginTable("scanTable", 6, flags)) {
            ImGui.tableSetupColumn("#", 0, 0.3f);
            ImGui.tableSetupColumn("Pipe Name", 0, 1.5f);
            ImGui.tableSetupColumn("Account", 0, 1.2f);
            ImGui.tableSetupColumn("World", 0, 0.5f);
            ImGui.tableSetupColumn("Status", 0, 0.8f);
            ImGui.tableSetupColumn("Actions", 0, 0.8f);
            ImGui.tableHeadersRow();

            for (int i = 0; i < scanResults.size(); i++) {
                ConnectCommand.PipeInfo info = scanResults.get(i);
                ImGui.tableNextRow();

                ImGui.tableSetColumnIndex(0);
                ImGui.text(String.valueOf(i + 1));

                ImGui.tableSetColumnIndex(1);
                ImGui.text(info.pipeName());

                ImGui.tableSetColumnIndex(2);
                if (info.displayName() != null && !info.displayName().isEmpty()) {
                    ImGui.text(info.displayName());
                } else {
                    ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f, "(unknown)");
                }

                ImGui.tableSetColumnIndex(3);
                ImGui.text(info.worldId() > 0 ? "W" + info.worldId() : "-");

                ImGui.tableSetColumnIndex(4);
                if (info.loggedIn()) {
                    ImGui.textColored(ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B, 1f, "Online");
                    if (info.isMember()) {
                        ImGui.sameLine();
                        ImGui.textColored(ImGuiTheme.YELLOW_R, ImGuiTheme.YELLOW_G, ImGuiTheme.YELLOW_B, 1f, "[M]");
                    }
                } else if (info.displayName() != null && !info.displayName().isEmpty()) {
                    ImGui.textColored(ImGuiTheme.CYAN_R, ImGuiTheme.CYAN_G, ImGuiTheme.CYAN_B, 1f, "Lobby");
                } else {
                    ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f, "Offline");
                }

                ImGui.tableSetColumnIndex(5);
                // Check if already connected
                boolean alreadyConnected = false;
                for (Connection conn : ctx.getConnections()) {
                    if (conn.getName().equals(info.pipeName())) {
                        alreadyConnected = true;
                        break;
                    }
                }

                if (alreadyConnected) {
                    ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f, "Connected");
                } else {
                    ImGui.pushID("scan_connect_" + i);
                    if (ImGui.smallButton("Connect")) {
                        String pipeName = info.pipeName();
                        executor.submit(() -> {
                            Command cmd = registry.resolve("connect");
                            if (cmd != null) {
                                cmd.execute(new ParsedCommand("connect", List.of(pipeName), Map.of()), ctx);
                            }
                        });
                    }
                    ImGui.popID();
                }
            }

            ImGui.endTable();
        }
    }

    private void renderConnectionsTable(CliContext ctx) {
        var connections = ctx.getConnections();
        if (connections.isEmpty()) {
            GuiHelpers.textMuted("No active connections. Use Scan or Quick Connect above.");
            return;
        }

        String activeName = ctx.getActiveConnectionName();
        String mountedName = ctx.getMountedConnectionName();

        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
        if (ImGui.beginTable("connTable", 6, flags)) {
            ImGui.tableSetupColumn("Name", 0, 1.2f);
            ImGui.tableSetupColumn("Account", 0, 1.0f);
            ImGui.tableSetupColumn("World", 0, 0.5f);
            ImGui.tableSetupColumn("Status", 0, 0.6f);
            ImGui.tableSetupColumn("Active", 0, 0.4f);
            ImGui.tableSetupColumn("Actions", 0, 1.5f);
            ImGui.tableHeadersRow();

            int idx = 0;
            for (Connection conn : connections) {
                ImGui.tableNextRow();
                boolean isActive = conn.getName().equals(activeName);
                boolean isMounted = conn.getName().equals(mountedName);

                ImGui.tableSetColumnIndex(0);
                if (isActive) {
                    ImGui.textColored(ImGuiTheme.CYAN_R, ImGuiTheme.CYAN_G, ImGuiTheme.CYAN_B, 1f, conn.getName());
                } else {
                    ImGui.text(conn.getName());
                }

                ImGui.tableSetColumnIndex(1);
                String account = conn.getAccountName();
                ImGui.text(account != null ? account : "-");

                ImGui.tableSetColumnIndex(2);
                Map<String, Object> info = conn.getAccountInfo();
                if (info != null) {
                    Object worldId = info.get("world_id");
                    if (worldId instanceof Number n && n.intValue() > 0) {
                        ImGui.text("W" + n.intValue());
                    } else {
                        ImGui.text("-");
                    }
                } else {
                    ImGui.text("-");
                }

                ImGui.tableSetColumnIndex(3);
                if (conn.isAlive()) {
                    ImGui.textColored(ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B, 1f, "Alive");
                } else {
                    ImGui.textColored(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 1f, "Dead");
                }

                ImGui.tableSetColumnIndex(4);
                if (isActive) {
                    ImGui.textColored(ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B, 1f, "*");
                }

                ImGui.tableSetColumnIndex(5);
                ImGui.pushID("conn_actions_" + idx);

                if (!isActive) {
                    if (ImGui.smallButton("Set Active")) {
                        ctx.setActive(conn.getName());
                    }
                    ImGui.sameLine();
                }

                if (isMounted) {
                    if (ImGui.smallButton("Unmount")) {
                        ctx.unmount();
                    }
                } else {
                    if (ImGui.smallButton("Mount")) {
                        ctx.mount(conn.getName());
                    }
                }

                ImGui.sameLine();
                if (GuiHelpers.smallButtonDanger(Icons.POWER + " Disconnect")) {
                    String name = conn.getName();
                    executor.submit(() -> ctx.disconnect(name, true));
                }

                ImGui.popID();
                idx++;
            }

            ImGui.endTable();
        }
    }
}
