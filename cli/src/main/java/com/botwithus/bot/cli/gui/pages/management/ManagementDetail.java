package com.botwithus.bot.cli.gui.pages.management;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.pages.management.ManagementState.DetailTab;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiStyleVar;

import java.util.List;

/**
 * The detail pane beside the list: the selected script's header, its
 * Overview, Settings, Script UI and Activity tabs, and a footer with Stop and
 * Restart, or Start.
 */
final class ManagementDetail {

    private static final float TILE_EM = 2.667f;

    private final ManagementWidgets w;
    private final OverviewTab overview;
    private final SettingsTab settings;
    private final ActivityTab activity;

    ManagementDetail(ManagementWidgets widgets) {
        this.w = widgets;
        this.overview = new OverviewTab(widgets);
        this.settings = new SettingsTab(widgets);
        this.activity = new ActivityTab(widgets);
    }

    /** Draws the pane at the cursor, {@code width} by {@code height}. */
    void render(ManagementView view, ManagementRow row, ManagementState state, float width, float height) {
        ImGuiTheme.Metrics m = w.m();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + height, ImGuiTheme.COL_SURFACE);
        draw.addLine(x, y, x, y + height, ImGuiTheme.COL_BORDER, m.hairline());
        float top = header(draw, row, x + m.u(4), y + m.u(4), width - m.u(8));
        top = tabs(row, state, x, top, width);
        float footH = m.controlHeight() + m.u(3) * 2f;
        ImGui.setCursorScreenPos(x + m.hairline(), top);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, m.u(4), m.u(4));
        ImGui.beginChild("##mgmt-detail-body", width - m.hairline(), y + height - footH - top,
                ImGuiChildFlags.AlwaysUseWindowPadding, 0);
        ImGui.popStyleVar();
        float bodyW = ImGui.getContentRegionAvailX();
        switch (state.tab(row)) {
            case OVERVIEW -> overview.render(view, row, state, bodyW);
            case SETTINGS -> settings.render(row, state, bodyW);
            case SCRIPT_UI -> settings.renderScriptUi(row, state, bodyW);
            case ACTIVITY -> activity.render(row, bodyW);
        }
        ImGui.endChild();
        footer(row, state, x, y + height - footH, width);
    }

    /** The robot tile, name and version over the Applies to line, and the state on the right; returns the y under. */
    private float header(ImDrawList draw, ManagementRow row, float x, float y, float width) {
        Controls ui = w.ui();
        ImGuiTheme.Metrics m = w.m();
        float tile = w.fs() * TILE_EM;
        RunState run = row.health().state();
        ui.iconTile(draw, x, y, tile, Icons.ROBOT, ManagementWidgets.tileFg(run), ManagementWidgets.tileBg(run));
        float stateW = w.stateWidth(run);
        w.state(draw, x + width - stateW, y, tile, run);
        float tx = x + tile + m.u(3);
        float textW = width - tile - m.u(3) - stateW - m.u(2);
        ImFont nameFont = ui.fonts().bodyMedium();
        float nameH = nameFont.getFontSize();
        float byH = ui.fonts().caption().getFontSize();
        float ty = y + (tile - nameH - byH - m.u(0.5f)) * 0.5f;
        String version = row.about().versionLabel();
        float versionW = version.isEmpty() ? 0f : ui.width(ui.fonts().monoCaption(), version) + m.u(1.5f);
        String name = ui.ellipsize(nameFont, row.name(), Math.max(0f, textW - versionW));
        ui.text(draw, nameFont, tx, ty, ImGuiTheme.COL_FG, name);
        if (!version.isEmpty()) {
            float vx = tx + ui.width(nameFont, name) + m.u(1.5f);
            ui.text(draw, ui.fonts().monoCaption(), vx, ty + nameH - ui.fonts().monoCaption().getFontSize(),
                    ImGuiTheme.COL_FG2, version);
        }
        ui.text(draw, ui.fonts().caption(), tx, ty + nameH + m.u(0.5f), ImGuiTheme.COL_FG2,
                ui.ellipsize(ui.fonts().caption(), row.appliesLine(), textW));
        return y + tile;
    }

    /** The tab strip with its rule under it; returns the y under the rule. */
    private float tabs(ManagementRow row, ManagementState state, float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        float ty = y + m.u(3);
        ImGui.setCursorScreenPos(x + m.u(4), ty);
        List<DetailTab> tabs = ManagementState.tabsFor(row);
        int clicked = w.tabs("##mgmt-tabs", tabs.stream().map(DetailTab::label).toList(),
                tabs.indexOf(state.tab(row)));
        if (clicked >= 0) {
            state.showTab(tabs.get(clicked));
        }
        float ruleY = ty + w.tabHeight();
        ImGui.getWindowDrawList().addLine(x, ruleY, x + width, ruleY, ImGuiTheme.COL_BORDER, m.hairline());
        return ruleY + m.hairline();
    }

    /** Stop and Restart while it runs; Start otherwise. */
    private void footer(ManagementRow row, ManagementState state, float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        ImGui.getWindowDrawList().addLine(x, y, x + width, y, ImGuiTheme.COL_BORDER, m.hairline());
        ImGui.setCursorScreenPos(x + m.u(4), y + m.u(3));
        String name = row.name();
        if (!row.health().state().isRunning()) {
            if (w.ui().button("##mgmt-detail-start", Icons.PLAY, "Start", Tone.SOFT, true)) {
                state.model().start(name);
            }
            return;
        }
        if (w.ui().button("##mgmt-detail-stop", Icons.STOP, "Stop", Tone.STOP, true)) {
            state.model().stop(name);
        }
        ImGui.sameLine(0f, m.u(2));
        if (w.ui().button("##mgmt-detail-restart", Icons.REDO, "Restart", Tone.GHOST, true)) {
            state.model().restart(name);
        }
    }
}
