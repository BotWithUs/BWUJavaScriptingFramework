package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

/**
 * The detail pane's Clients tab: each client's runner with its state, Stop /
 * Run / Restart and a settings link, then "Start on more clients…" and the
 * management scripts that manage the script.
 */
final class DetailClientsTab {

    private static final float CHIP_EM = 1.6f;

    private final InstalledWidgets w;

    DetailClientsTab(InstalledWidgets widgets) {
        this.w = widgets;
    }

    /** Draws the tab from the cursor, {@code width} wide. */
    void render(InstalledScript s, InstalledState state, float width) {
        ImGuiTheme.Metrics m = w.m();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImDrawList draw = ImGui.getWindowDrawList();
        w.heading(draw, x, y, "Runs on");
        float cy = y + w.ui().fonts().captionMedium().getFontSize() + m.u(2);
        cy = s.runs().isEmpty() ? note(draw, x, cy, "Not on any client yet.") : runList(s, state, x, cy, width);
        if (s.isStartable()) {
            ImGui.setCursorScreenPos(x, cy + m.u(2));
            if (w.ui().button("##detail-start-more", Icons.PLAY, "Start on more clients…", Tone.GHOST, true)) {
                state.openStartOn(s.key());
            }
            cy += m.u(2) + m.controlHeight();
        }
        if (!s.managedBy().isEmpty()) {
            cy = managedBy(s, state, x, cy + m.u(4), width);
        }
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, cy - y);
    }

    private float note(ImDrawList draw, float x, float y, String text) {
        w.ui().text(draw, w.ui().fonts().caption(), x, y, ImGuiTheme.COL_FG2, text);
        return y + w.ui().fonts().caption().getFontSize();
    }

    private float rowHeight() {
        Controls ui = w.ui();
        return w.m().u(1.5f) * 2f + ui.fonts().small().getFontSize() + ui.fonts().caption().getFontSize()
                + w.m().u(0.5f);
    }

    private float runList(InstalledScript s, InstalledState state, float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float h = rowHeight() * s.runs().size();
        draw.addRect(x + 0.5f, y + 0.5f, x + width - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER, m.radius());
        float ry = y;
        for (ClientRun run : s.runs()) {
            if (ry > y) {
                draw.addLine(x, ry, x + width, ry, ImGuiTheme.COL_BORDER, m.hairline());
            }
            runRow(s, run, state, x, ry, width);
            ry += rowHeight();
        }
        return y + h;
    }

    private void runRow(InstalledScript s, ClientRun run, InstalledState state, float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        Controls ui = w.ui();
        ImDrawList draw = ImGui.getWindowDrawList();
        float buttons = m.controlSmallHeight() * 2f + m.u(0.5f) + m.u(1);
        float textW = width - m.u(3) - buttons;
        ImFont name = ui.fonts().small();
        ImFont detail = ui.fonts().caption();
        float ty = y + m.u(1.5f);
        ui.text(draw, name, x + m.u(3), ty, ImGuiTheme.COL_FG, ui.ellipsize(name, run.clientName(), textW));
        ui.text(draw, detail, x + m.u(3), ty + name.getFontSize() + m.u(0.5f),
                InstalledWidgets.detailColor(run.state()), ui.ellipsize(detail, run.detail(), textW));
        float by = y + (rowHeight() - m.controlSmallHeight()) * 0.5f;
        float bx = x + width - m.u(1) - m.controlSmallHeight();
        String id = "##run:" + s.key() + ":" + run.clientId();
        if (run.hasSettings() && run.state() != RunnerState.OFFLINE) {
            ImGui.setCursorScreenPos(bx, by);
            if (w.iconButton(id + ":settings", Icons.SLIDERS, ImGuiTheme.COL_FG2, ImGuiTheme.COL_ELEVATED,
                    "Settings on " + run.clientName())) {
                state.model().openSettings(s.key(), run.clientId());
            }
            bx -= m.controlSmallHeight() + m.u(0.5f);
        }
        ImGui.setCursorScreenPos(bx, by);
        runAction(s, run, state, id);
    }

    /** Stop while it runs, Run once stopped, Restart after a crash; nothing it cannot act on. */
    private void runAction(InstalledScript s, ClientRun run, InstalledState state, String id) {
        String who = run.clientName();
        switch (run.state()) {
            case RUNNING, STALLED -> {
                if (w.iconButton(id + ":stop", Icons.STOP, ImGuiTheme.COL_DANGER, ImGuiTheme.COL_DANGER_SOFT,
                        "Stop on " + who)) {
                    state.model().stop(s.key(), run.clientId());
                }
            }
            case STOPPED -> {
                if (w.iconButton(id + ":run", Icons.PLAY, ImGuiTheme.COL_ACCENT, ImGuiTheme.COL_ACCENT_SOFT,
                        "Run on " + who)) {
                    state.model().run(s.key(), run.clientId());
                }
            }
            case CRASHED -> {
                if (w.iconButton(id + ":restart", Icons.REDO, ImGuiTheme.COL_ACCENT, ImGuiTheme.COL_ACCENT_SOFT,
                        "Restart on " + who)) {
                    state.model().run(s.key(), run.clientId());
                }
            }
            case CUT_OFF, OFFLINE -> { }
        }
    }

    /** The management scripts managing this script, as chips leading to Management; returns the y under. */
    private float managedBy(InstalledScript s, InstalledState state, float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        Controls ui = w.ui();
        ImDrawList draw = ImGui.getWindowDrawList();
        w.heading(draw, x, y, "Managed by");
        float cy = y + ui.fonts().captionMedium().getFontSize() + m.u(2);
        float cx = x;
        float h = w.fs() * CHIP_EM;
        for (String name : s.managedBy()) {
            float cw = m.u(2) * 2f + ui.width(ui.fonts().caption(), Icons.ROBOT) + m.u(1.25f)
                    + ui.width(ui.fonts().caption(), name);
            if (cx > x && cx + cw > x + width) {
                cx = x;
                cy += h + m.u(1);
            }
            ImGui.setCursorScreenPos(cx, cy);
            if (ImGui.invisibleButton("##managed:" + name, cw, h)) {
                state.openManagement();
            }
            chip(draw, cx, cy, cw, h, name, ImGui.isItemHovered());
            cx += cw + m.u(1);
        }
        return note(draw, x, cy + h + m.u(2), "On some of these clients. See Management for which.");
    }

    private void chip(ImDrawList draw, float x, float y, float cw, float h, String name, boolean isHovered) {
        ImGuiTheme.Metrics m = w.m();
        Controls ui = w.ui();
        if (isHovered) {
            draw.addRectFilled(x, y, x + cw, y + h, ImGuiTheme.COL_ELEVATED, m.radiusSmall());
        }
        draw.addRect(x + 0.5f, y + 0.5f, x + cw - 0.5f, y + h - 0.5f,
                isHovered ? ImGuiTheme.COL_BORDER_HOVER : ImGuiTheme.COL_BORDER, m.radiusSmall());
        ui.textCentredY(draw, ui.fonts().caption(), x + m.u(2), y, h, ImGuiTheme.COL_INFO, Icons.ROBOT);
        float tx = x + m.u(2) + ui.width(ui.fonts().caption(), Icons.ROBOT) + m.u(1.25f);
        ui.textCentredY(draw, ui.fonts().caption(), tx, y, h, ImGuiTheme.COL_FG, name);
    }
}
