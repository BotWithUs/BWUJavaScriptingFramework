package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.gui.usermode.board.BoardStatus;
import com.botwithus.bot.cli.gui.usermode.board.ClientBoard;
import com.botwithus.bot.cli.gui.usermode.board.ClientStatus;
import com.botwithus.bot.cli.gui.usermode.board.ClientView;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import java.util.ArrayList;
import java.util.List;

/**
 * The one status bar, shown in both modes along the bottom of the window.
 *
 * <p>Left: the connection state with a coloured dot, then client, running and
 * needs-attention counts. Right: the mounted connection and the script watcher
 * (Advanced only), then the F12 hint naming the mode it switches to.</p>
 */
public class StatusBar {

    /** A run of text; {@code col} is the colour, {@code dot} a status dot drawn first. */
    private record Run(String text, int col, Integer dot) {
        static Run plain(String text) {
            return new Run(text, ImGuiTheme.COL_FG2, null);
        }

        static Run strong(String text) {
            return new Run(text, ImGuiTheme.COL_FG, null);
        }
    }

    private final Controls ui;

    public StatusBar(Controls ui) {
        this.ui = ui;
    }

    public float height() {
        return ui.m().statusBarHeight();
    }

    public void render(ClientBoard board, AppMode mode) {
        ImGuiTheme.Metrics m = ui.m();
        float h = height();
        Controls.pushColor(ImGuiCol.ChildBg, ImGuiTheme.COL_SURFACE);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.beginChild("##statusbar", 0f, h, false,
                ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        ImGui.popStyleVar();
        ImGui.popStyleColor();
        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getWindowPosX();
        float y = ImGui.getWindowPosY();
        float w = ImGui.getWindowWidth();
        draw.addLine(x, y + 0.5f, x + w, y + 0.5f, ImGuiTheme.COL_BORDER, m.hairline());

        List<ClientView> clients = board.clients();
        BoardStatus status = board.status();
        float cx = x + m.u(3);
        for (List<Run> segment : leftSegments(clients, status)) {
            cx = drawSegment(draw, segment, cx, y, h) + m.u(4);
        }
        List<List<Run>> right = rightSegments(status, mode);
        float rx = x + w - m.u(3);
        for (int i = right.size() - 1; i >= 0; i--) {
            rx -= segmentWidth(right.get(i));
            drawSegment(draw, right.get(i), rx, y, h);
            rx -= m.u(4);
        }
        ImGui.endChild();
    }

    private static List<List<Run>> leftSegments(List<ClientView> clients, BoardStatus status) {
        List<List<Run>> out = new ArrayList<>();
        out.add(connectionSegment(clients, status));
        out.add(List.of(Run.plain(clients.size() + (clients.size() == 1 ? " client" : " clients"))));
        long running = clients.stream().filter(c -> c.status().isRunning()).count();
        out.add(List.of(Run.plain(running + " running")));
        long attention = clients.stream().filter(c -> c.status().needsAttention()).count();
        if (attention > 0) {
            out.add(List.of(new Run(attention + (attention == 1 ? " needs" : " need") + " attention",
                    ImGuiTheme.COL_DANGER, null)));
        }
        return out;
    }

    private static List<Run> connectionSegment(List<ClientView> clients, BoardStatus status) {
        if (status.hostOffline()) {
            return List.of(new Run("Disconnected · gave up", ImGuiTheme.COL_FG2, ImGuiTheme.COL_DANGER));
        }
        if (clients.isEmpty() || status.activeClient() == null) {
            return List.of(new Run("No connection", ImGuiTheme.COL_FG2, ImGuiTheme.COL_DANGER));
        }
        String name = status.activeClient();
        ClientStatus active = clients.stream().filter(c -> c.id().equals(name)).findFirst()
                .map(ClientView::status).orElse(new ClientStatus.Idle());
        return switch (active) {
            case ClientStatus.Reconnecting r -> List.of(
                    new Run("Reconnecting ", ImGuiTheme.COL_FG2, ImGuiTheme.COL_WARN), Run.strong(name),
                    Run.plain(" · attempt " + r.attempt()));
            case ClientStatus.Lost ignored -> List.of(
                    new Run("Lost ", ImGuiTheme.COL_FG2, ImGuiTheme.COL_DANGER), Run.strong(name));
            case ClientStatus.Running ignored -> activeRuns(name);
            case ClientStatus.Idle ignored -> activeRuns(name);
            case ClientStatus.Loading ignored -> activeRuns(name);
            case ClientStatus.Crashed ignored -> activeRuns(name);
        };
    }

    private static List<Run> activeRuns(String name) {
        return List.of(new Run("Active ", ImGuiTheme.COL_FG2, ImGuiTheme.COL_ACCENT), Run.strong(name));
    }

    private static List<List<Run>> rightSegments(BoardStatus status, AppMode mode) {
        List<List<Run>> out = new ArrayList<>();
        if (mode == AppMode.ADVANCED) {
            if (status.mountedClient() != null) {
                out.add(List.of(Run.plain("mounted "), Run.strong(status.mountedClient())));
            }
            if (status.watchingScripts()) {
                out.add(List.of(Run.plain("watching "), Run.strong("scripts/")));
            }
        }
        out.add(List.of(Run.plain("F12 " + (mode == AppMode.ADVANCED ? "Normal" : "Advanced"))));
        return out;
    }

    private float segmentWidth(List<Run> runs) {
        ImFont font = ui.fonts().monoCaption();
        float w = 0f;
        for (Run r : runs) {
            if (r.dot() != null) {
                w += ui.m().dot() + ui.m().u(1.5f);
            }
            w += ui.width(font, r.text());
        }
        return w;
    }

    private float drawSegment(ImDrawList draw, List<Run> runs, float x, float y, float h) {
        ImFont font = ui.fonts().monoCaption();
        float cx = x;
        for (Run r : runs) {
            if (r.dot() != null) {
                float rad = ui.m().dot() * 0.5f;
                draw.addCircleFilled(cx + rad, y + h * 0.5f, rad, r.dot());
                cx += ui.m().dot() + ui.m().u(1.5f);
            }
            ui.textCentredY(draw, font, cx, y, h, r.col(), r.text());
            cx += ui.width(font, r.text());
        }
        return cx;
    }
}
