package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Connections management panel — scan for pipes, connect/disconnect, switch active, mount/unmount.
 */
public class ConnectionsPanel implements GuiPanel {

    private final ExecutorService executor;
    private final ImString scanFilter = new ImString("BotWithUs", 128);

    private record PipeInfo(String pipeName, String displayName, int worldId, boolean loggedIn, boolean isMember) {}

    private List<PipeInfo> scanResults;
    private volatile boolean scanning = false;
    private volatile String scanStatus;

    public ConnectionsPanel(ExecutorService executor) {
        this.executor = executor;
    }

    @Override
    public String title() {
        return "Connections";
    }

    @Override
    public void render(CliContext ctx) {
        // Scan controls
        ImGui.text("Pipe Filter:");
        ImGui.sameLine();
        ImGui.pushItemWidth(200);
        ImGui.inputText("##scanFilter", scanFilter);
        ImGui.popItemWidth();
        ImGui.sameLine();

        if (scanning) {
            ImGui.beginDisabled();
            ImGui.button("Scanning...");
            ImGui.endDisabled();
        } else {
            if (ImGui.button("Scan")) {
                startScan(ctx);
            }
        }

        ImGui.sameLine();
        if (ImGui.button("Quick Connect")) {
            executor.submit(() -> {
                ctx.connect(null);
                probeConnection(ctx, ctx.getActiveConnectionName());
            });
        }

        if (scanStatus != null) {
            ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f, scanStatus);
        }

        ImGui.spacing();

        // Scan results table
        if (scanResults != null && !scanResults.isEmpty()) {
            ImGui.text("Available Pipes:");
            renderScanTable(ctx);
            ImGui.spacing();
        }

        // Active connections table
        ImGui.separator();
        ImGui.text("Active Connections:");
        ImGui.spacing();
        renderConnectionsTable(ctx);
    }

    private void startScan(CliContext ctx) {
        scanning = true;
        scanStatus = "Scanning...";
        executor.submit(() -> {
            try {
                String prefix = scanFilter.get().trim();
                if (prefix.isEmpty()) prefix = "BotWithUs";
                List<String> pipes = PipeClient.scanPipes(prefix);
                if (pipes.isEmpty()) {
                    scanResults = List.of();
                    scanStatus = "No pipes found.";
                } else {
                    List<PipeInfo> infos = new ArrayList<>();
                    for (String pipeName : pipes) {
                        infos.add(probePipe(pipeName));
                    }
                    scanResults = infos;
                    scanStatus = "Found " + pipes.size() + " pipe(s).";
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
                PipeInfo info = scanResults.get(i);
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
                            ctx.connect(pipeName);
                            probeConnection(ctx, pipeName);
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
            ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f,
                    "No active connections. Use Scan or Quick Connect above.");
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
                if (ImGui.smallButton("Disconnect")) {
                    String name = conn.getName();
                    executor.submit(() -> ctx.disconnect(name, true));
                }

                ImGui.popID();
                idx++;
            }

            ImGui.endTable();
        }
    }

    private PipeInfo probePipe(String pipeName) {
        try (PipeClient pipe = new PipeClient(pipeName)) {
            RpcClient rpc = new RpcClient(pipe);
            rpc.setTimeout(3_000);
            Map<String, Object> r = rpc.callSync("get_account_info", Map.of());
            String displayName = getString(r, "display_name");
            if (displayName == null || displayName.isEmpty()) {
                displayName = getString(r, "jx_display_name");
            }
            boolean loggedIn = getBool(r, "logged_in");
            boolean isMember = getBool(r, "is_member");

            int worldId = -1;
            if (loggedIn) {
                try {
                    Map<String, Object> wr = rpc.callSync("get_current_world", Map.of());
                    worldId = getInt(wr, "world_id");
                } catch (Exception e) {
                    System.err.println("[ConnectionsPanel] Failed to get world for " + pipeName + ": " + e.getMessage());
                }
            }

            return new PipeInfo(pipeName, displayName, worldId, loggedIn, isMember);
        } catch (Exception e) {
            System.err.println("[ConnectionsPanel] Probe failed for " + pipeName + ": " + e.getMessage());
            return new PipeInfo(pipeName, null, -1, false, false);
        }
    }

    private void probeConnection(CliContext ctx, String connName) {
        if (connName == null) return;
        Connection found = null;
        for (Connection c : ctx.getConnections()) {
            if (c.getName().equals(connName)) {
                found = c;
                break;
            }
        }
        if (found == null) return;
        final Connection conn = found;

        try {
            Map<String, Object> info = conn.getRpc().callSync("get_account_info", Map.of());
            String displayName = getString(info, "display_name");
            if (displayName == null || displayName.isEmpty()) {
                displayName = getString(info, "jx_display_name");
            }
            if (displayName != null && !displayName.isEmpty()) {
                conn.setAccountName(displayName);
                conn.setAccountInfo(info);
                if (ctx.getAutoStartManager() != null) {
                    conn.getRuntime().setOnStateChange(() -> ctx.getAutoStartManager().saveState(conn));
                    ctx.getAutoStartManager().onConnectionEstablished(conn, displayName);
                }
            }
        } catch (Exception e) {
            System.err.println("[ConnectionsPanel] Probe connection failed for " + connName + ": " + e.getMessage());
        }
    }

    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    private static int getInt(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        return -1;
    }

    private static boolean getBool(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Boolean b) return b;
        return false;
    }
}
