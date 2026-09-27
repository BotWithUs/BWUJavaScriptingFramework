package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.ImGuiTheme;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The detail pane's middle and foot: the link or reconnect numbers, which
 * depend on the row's state, the history timeline, and the action buttons.
 */
final class DetailSections {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final float TIME_EM = 4.267f;
    private static final float TIMELINE_DOT_PX = 8f;

    private final ConnectionWidgets w;
    private final Controls ui;
    private final ConnectionsModel model;

    DetailSections(ConnectionWidgets widgets, ConnectionsModel model) {
        this.w = widgets;
        this.ui = widgets.ui();
        this.model = model;
    }

    // ── Link, by state ─────────────────────────────────────────────────────

    /** The section that depends on where the row is in its life. Returns the y under it. */
    float link(ConnectionDetail detail, boolean isAutoConnect, float x, float y, float width) {
        ConnectionRow row = detail.row();
        float bottom = switch (row.link()) {
            case LinkState.Connected _, LinkState.Identifying _, LinkState.Resuming _ -> live(row, x, y, width);
            case LinkState.NotResponding n -> notResponding(n, detail.policy(), x, y, width);
            case LinkState.Found _ -> notes(x, y, width, "Not connected", isAutoConnect
                    ? "Auto-connect picks this up on the next scan. Connect now to skip the wait."
                    : "Auto-connect is off. Connect to read the account and start scripts on it.");
            case LinkState.Closed c -> notes(x, y, width, "Client closed", closedNote(c, detail));
        };
        return bottom + ui.m().u(2);
    }

    private float live(ConnectionRow row, float x, float y, float width) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float cy = w.sectionTitle(draw, x, y, "Link");
        if (row.stats().isPresent()) {
            LinkStats stats = row.stats().get();
            cy = w.keyValue(draw, x, cy, width, "Connected for", ConnectionText.uptime(stats.uptime()),
                    ImGuiTheme.COL_FG);
            cy = w.keyValue(draw, x, cy, width, "RPC calls", ConnectionText.count(stats.calls()), ImGuiTheme.COL_FG);
            cy = w.keyValue(draw, x, cy, width, "RPC errors", ConnectionText.count(stats.errors()),
                    stats.errors() > 0 ? ImGuiTheme.COL_DANGER : ImGuiTheme.COL_FG);
            cy = w.keyValue(draw, x, cy, width, "Avg latency", ConnectionText.rpc(stats.avgRpcMs()),
                    ImGuiTheme.COL_FG);
        }
        switch (row.link()) {
            case LinkState.Resuming r -> cy = w.keyValue(draw, x, cy, width, "Came back from",
                    r.previousPipe().orElse("an earlier run"), ImGuiTheme.COL_FG);
            case LinkState.Connected _, LinkState.Identifying _, LinkState.NotResponding _, LinkState.Found _,
                 LinkState.Closed _ -> { }
        }
        String console = (row.isConsoleTarget() ? "target" : ConnectionText.NONE)
                + (row.isOutputFilter() ? " · output filtered" : "");
        return w.keyValue(draw, x, cy, width, "Console", console, ImGuiTheme.COL_FG);
    }

    private float notResponding(LinkState.NotResponding n, String policy, float x, float y, float width) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float cy = w.sectionTitle(draw, x, y, n.isRetrying() ? "Reconnecting" : "Not retrying");
        cy = w.keyValue(draw, x, cy, width, "No reply for", ConnectionText.downFor(n.downFor()), ImGuiTheme.COL_WARN);
        cy = w.keyValue(draw, x, cy, width, n.isRetrying() ? "Attempt" : "Last attempt",
                ConnectionText.attempt(n.attempt(), n.maxAttempts()), ImGuiTheme.COL_FG);
        if (n.nextIn().isPresent()) {
            cy = w.keyValue(draw, x, cy, width, "Next try in", ConnectionText.seconds(n.nextIn().get()),
                    ImGuiTheme.COL_FG);
        }
        cy = w.keyValue(draw, x, cy, width, "Backoff", policy, ImGuiTheme.COL_FG);
        if (!n.isRetrying()) {
            cy = w.note(draw, x, cy + ui.m().u(1), width,
                    "Retrying was stopped. Retry now to try again, or disconnect to let it go.");
        }
        return cy;
    }

    private static String closedNote(LinkState.Closed c, ConnectionDetail detail) {
        if (c.isClientGone()) {
            return "The game client exited, so this pipe cannot come back and retrying will not help. "
                    + "Forget it, or wait for the account on a new pipe.";
        }
        String when = "The game client closed " + ConnectionText.agoLong(c.ago()) + ". ";
        if (detail.row().accountUuid().isEmpty()) {
            return when + "It had no account UUID, so it cannot be recognised if it comes back.";
        }
        return detail.resumeAfterRestart().orElse(false)
                ? when + "When the same account shows up on any pipe, its scripts restart on their own."
                : when + "When the same account shows up on any pipe, it moves back to Connected.";
    }

    private float notes(float x, float y, float width, String title, String text) {
        ImDrawList draw = ImGui.getWindowDrawList();
        return w.note(draw, x, w.sectionTitle(draw, x, y, title), width, text);
    }

    // ── History ────────────────────────────────────────────────────────────

    /** The timeline, newest first. Returns the y under it. */
    float history(List<TimelineEntry> entries, float x, float y, float width) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float cy = w.sectionTitle(draw, x, y, "History");
        float timeW = ui.fonts().body().getFontSize() * TIME_EM;
        float textX = x + TIMELINE_DOT_PX + m.u(2) + timeW + m.u(2);
        for (int i = 0; i < entries.size(); i++) {
            float next = entry(draw, entries.get(i), x, cy, textX, x + width);
            if (i + 1 < entries.size()) {
                float lx = x + TIMELINE_DOT_PX * 0.5f;
                draw.addLine(lx, cy + TIMELINE_DOT_PX + m.u(1), lx, next, ImGuiTheme.COL_BORDER, m.hairline());
            }
            cy = next;
        }
        return cy;
    }

    private float entry(ImDrawList draw, TimelineEntry entry, float x, float y, float textX, float right) {
        ImGuiTheme.Metrics m = ui.m();
        ImFont bold = ui.fonts().captionMedium();
        ImFont plain = ui.fonts().caption();
        float lineH = w.lineHeight(plain);
        float r = TIMELINE_DOT_PX * 0.5f;
        draw.addCircleFilled(x + r, y + lineH * 0.5f, r, ConnectionWidgets.colour(entry.tone()));
        ui.text(draw, ui.fonts().monoCaption(), x + TIMELINE_DOT_PX + m.u(2), y + (lineH - plain.getFontSize()) * 0.5f,
                ImGuiTheme.COL_FG3, CLOCK.format(entry.at()));
        ui.text(draw, bold, textX, y, ImGuiTheme.COL_FG, entry.lead());
        float cy = y;
        if (!entry.detail().isEmpty()) {
            String tail = " · " + entry.detail();
            float leadW = ui.width(bold, entry.lead());
            if (textX + leadW + ui.width(plain, tail) <= right) {
                ui.text(draw, plain, textX + leadW, y, ImGuiTheme.COL_FG2, tail);
            } else {
                cy = w.note(draw, textX, y + lineH, right - textX, entry.detail()) - lineH;
            }
        }
        return cy + lineH + m.u(2);
    }

    // ── Actions ────────────────────────────────────────────────────────────

    /** The buttons for what the row offers, left to right, wrapping. Returns the y under them. */
    float actions(ConnectionRow row, float x, float y, float width) {
        ButtonFlow flow = new ButtonFlow(x, y, width);
        if (row.can(RowAction.CONSOLE_TARGET)) {
            String label = row.isConsoleTarget() ? "Console target" : "Make console target";
            if (flow.button(ui, "##d-target", Icons.CIRCLE_DOT, label, Tone.GHOST, !row.isConsoleTarget())) {
                model.setConsoleTarget(row);
            }
        }
        if (row.can(RowAction.CONNECT) && flow.button(ui, "##d-connect", Icons.LINK, "Connect", Tone.GHOST, true)) {
            model.connect(row);
        }
        if (row.can(RowAction.RETRY_NOW) && flow.button(ui, "##d-retry", Icons.REDO, "Retry now", Tone.GHOST, true)) {
            model.retryNow(row);
        }
        if (row.can(RowAction.STOP_RETRYING)
                && flow.button(ui, "##d-stop", Icons.STOP, "Stop retrying", Tone.STOP, true)) {
            model.stopRetrying(row);
        }
        if (row.can(RowAction.DISCONNECT)
                && flow.button(ui, "##d-disconnect", Icons.POWER, "Disconnect", Tone.STOP, true)) {
            model.disconnect(row);
        }
        if (row.can(RowAction.FORGET) && flow.button(ui, "##d-forget", Icons.XMARK, "Forget", Tone.GHOST, true)) {
            model.forget(row);
        }
        return flow.bottom(ui.m().controlHeight());
    }

    /** Lays buttons left to right, wrapping at the pane's edge. */
    private static final class ButtonFlow {
        private final float left;
        private final float right;
        private float x;
        private float y;
        private boolean isEmpty = true;

        ButtonFlow(float left, float top, float width) {
            this.left = left;
            this.right = left + width;
            this.x = left;
            this.y = top;
        }

        boolean button(Controls ui, String id, String icon, String label, Tone tone, boolean enabled) {
            float bw = ui.buttonWidth(icon, label, tone);
            float gap = ui.m().u(2);
            if (x > left && x + bw > right) {
                x = left;
                y += ui.m().controlHeight() + gap;
            }
            ImGui.setCursorScreenPos(x, y);
            boolean clicked = ui.button(id, icon, label, tone, enabled);
            x += bw + gap;
            isEmpty = false;
            return clicked;
        }

        float bottom(float rowH) {
            return isEmpty ? y : y + rowH;
        }
    }
}
