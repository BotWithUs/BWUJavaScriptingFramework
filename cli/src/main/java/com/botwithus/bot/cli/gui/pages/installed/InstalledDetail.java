package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.cli.gui.CategoryStyle;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.pages.installed.InstalledState.DetailTab;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiStyleVar;

import java.util.Arrays;
import java.util.List;

/**
 * The detail pane beside the list: the selected script's header, its Clients
 * and About tabs, and a footer with Stop on all and Start on…
 */
final class InstalledDetail {

    private static final float TILE_EM = 2.667f;
    private static final List<String> TAB_LABELS = List.of("Clients", "About");

    private final InstalledWidgets w;
    private final DetailClientsTab clients;
    private final DetailAboutTab about;

    InstalledDetail(InstalledWidgets widgets) {
        this.w = widgets;
        this.clients = new DetailClientsTab(widgets);
        this.about = new DetailAboutTab(widgets);
    }

    /** Draws the pane at the cursor, {@code width} by {@code height}. */
    void render(InstalledView view, InstalledScript s, InstalledState state, float width, float height) {
        ImGuiTheme.Metrics m = w.m();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + height, ImGuiTheme.COL_SURFACE);
        draw.addLine(x, y, x, y + height, ImGuiTheme.COL_BORDER, m.hairline());
        float top = header(draw, s, x + m.u(4), y + m.u(4), width - m.u(8));
        top = tabs(s, state, x, top, width);
        float footH = m.controlHeight() + m.u(3) * 2f;
        ImGui.setCursorScreenPos(x + m.hairline(), top);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, m.u(4), m.u(4));
        ImGui.beginChild("##installed-detail-body", width - m.hairline(), y + height - footH - top,
                ImGuiChildFlags.AlwaysUseWindowPadding, 0);
        ImGui.popStyleVar();
        float bodyW = ImGui.getContentRegionAvailX();
        switch (state.tab()) {
            case CLIENTS -> clients.render(s, state, bodyW);
            case ABOUT -> about.render(view, s, state, bodyW);
        }
        ImGui.endChild();
        footer(s, state, x, y + height - footH, width, footH);
    }

    /** The icon, name and version, and the category and source line; returns the y under it. */
    private float header(ImDrawList draw, InstalledScript s, float x, float y, float width) {
        Controls ui = w.ui();
        ImGuiTheme.Metrics m = w.m();
        float tile = w.fs() * TILE_EM;
        IconTone tone = IconTone.of(s);
        ui.iconTile(draw, x, y, tile, CategoryStyle.icon(s.identity().category()), tone.fg(), tone.bg());
        float tx = x + tile + m.u(3);
        float nameH = ui.fonts().bodyMedium().getFontSize();
        float byH = ui.fonts().caption().getFontSize();
        float ty = y + (tile - nameH - byH - m.u(0.5f)) * 0.5f;
        String name = ui.ellipsize(ui.fonts().bodyMedium(), s.name(), width - tile - m.u(3));
        ui.text(draw, ui.fonts().bodyMedium(), tx, ty, ImGuiTheme.COL_FG, name);
        String version = ScriptRowPainter.versionLabel(s.identity().version());
        float vx = tx + ui.width(ui.fonts().bodyMedium(), name) + m.u(1.5f);
        ui.text(draw, ui.fonts().monoCaption(), vx, ty + nameH - ui.fonts().monoCaption().getFontSize(),
                ImGuiTheme.COL_FG2, version);
        ui.text(draw, ui.fonts().caption(), tx, ty + nameH + m.u(0.5f), ImGuiTheme.COL_FG2, byLine(s));
        return y + tile;
    }

    private static String byLine(InstalledScript s) {
        String where = switch (s.provenance().source()) {
            case STORE -> s.provenance().isLoaded() ? "from the Store" : "Store · not loaded";
            case LOCAL -> "local build";
        };
        return s.identity().categoryLabel() + " · " + where;
    }

    /** The tab strip with its rule under it; returns the y under the rule. */
    private float tabs(InstalledScript s, InstalledState state, float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        float ty = y + m.u(3);
        ImGui.setCursorScreenPos(x + m.u(4), ty);
        int selected = state.tab().ordinal();
        List<String> counts = Arrays.asList(Integer.toString(s.runs().size()), null);
        int clicked = w.tabs("##installed-tabs", TAB_LABELS, counts, selected);
        if (clicked >= 0) {
            state.showTab(DetailTab.values()[clicked]);
        }
        float ruleY = ty + w.tabHeight();
        ImGui.getWindowDrawList().addLine(x, ruleY, x + width, ruleY, ImGuiTheme.COL_BORDER, m.hairline());
        return ruleY + m.hairline();
    }

    /** Stop on all N (confirming inline) and Start on…, or Install again for a Store script that is not loaded. */
    private void footer(InstalledScript s, InstalledState state, float x, float y, float width, float h) {
        ImGuiTheme.Metrics m = w.m();
        ImGui.getWindowDrawList().addLine(x, y, x + width, y, ImGuiTheme.COL_BORDER, m.hairline());
        ImGui.setCursorScreenPos(x + m.u(4), y + m.u(3));
        if (s.provenance().isStoreNotLoaded()) {
            if (w.ui().button("##detail-install", Icons.BAG_SHOPPING, "Install again", Tone.SOFT, true)) {
                state.openStore();
            }
            return;
        }
        if (s.isActive()) {
            stopButton(s, state);
            ImGui.sameLine(0f, m.u(2));
        }
        if (w.ui().button("##detail-start", Icons.PLAY, "Start on…", Tone.GHOST, true)) {
            state.openStartOn(s.key());
        }
    }

    private void stopButton(InstalledScript s, InstalledState state) {
        if (!state.isStopArmed(s.key())) {
            if (w.ui().button("##detail-stop", Icons.STOP, "Stop on all " + s.activeCount(), Tone.STOP, true)) {
                state.armStop(s.key());
            }
            return;
        }
        if (w.ui().button("##detail-stop-confirm", Icons.STOP, "Confirm: stop on " + s.activeCount(),
                Tone.STOP, true)) {
            state.confirmStop(s.key());
        }
        ImGui.sameLine(0f, w.m().u(1));
        if (w.link("##detail-stop-cancel", null, "Cancel", w.m().controlHeight())) {
            state.disarmStop();
        }
    }
}
