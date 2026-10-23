package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

/**
 * The floating "Install N scripts" bar: it rises from the bottom of the list once
 * a row is ticked, and holds the page's only primary button. It is its own child
 * window drawn over the list, so the rows under it never take its clicks.
 */
final class InstallBar {

    private static final float SHADOW_PX = 4f;
    private static final String CLEAR = "Clear";

    private final StoreWidgets w;
    private final Controls ui;

    InstallBar(StoreWidgets widgets) {
        this.w = widgets;
        this.ui = widgets.ui();
    }

    /**
     * Draws the bar centred over the region x0..x1, its bottom {@code u(4)} above
     * {@code bottom}. Does nothing when nothing is ticked.
     */
    void render(StoreActions actions, boolean canInstall, float x0, float x1, float bottom) {
        int n = actions.selection().size();
        if (n == 0) {
            return;
        }
        ImGuiTheme.Metrics m = ui.m();
        String install = "Install " + n + " script" + (n == 1 ? "" : "s");
        String count = Integer.toString(n);
        String selected = " selected";
        float textW = ui.width(ui.fonts().smallMedium(), count) + ui.width(ui.fonts().small(), selected);
        float barH = m.controlHeight() + m.u(2) * 2f;
        float barW = m.u(4) + textW + m.u(3) + w.linkWidth(null, CLEAR) + m.u(3)
                + ui.buttonWidth(Icons.DOWNLOAD, install, Tone.PRIMARY) + m.u(2);
        float x = (x0 + x1 - barW) * 0.5f;
        float y = bottom - m.u(4) - barH - SHADOW_PX;
        ImGui.setCursorScreenPos(x, y);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        // Tall enough to hold the drop shadow too: the list under it is another window, so a
        // shadow drawn outside this one would be painted over.
        ImGui.beginChild("##install-bar", barW, barH + SHADOW_PX, false,
                ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        ImGui.popStyleVar();
        contents(actions, canInstall, install, count, selected, x, y, barW, barH);
        ImGui.endChild();
    }

    private void contents(StoreActions actions, boolean canInstall, String install, String count, String selected,
                          float x, float y, float barW, float barH) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y + SHADOW_PX, x + barW, y + barH + SHADOW_PX, ImGuiTheme.COL_SHADOW, m.radiusXl());
        draw.addRectFilled(x, y, x + barW, y + barH, ImGuiTheme.COL_ELEVATED, m.radiusXl());
        draw.addRect(x + 0.5f, y + 0.5f, x + barW - 0.5f, y + barH - 0.5f, ImGuiTheme.COL_BORDER_HOVER, m.radiusXl());
        float tx = x + m.u(4);
        ui.textCentredY(draw, ui.fonts().smallMedium(), tx, y, barH, ImGuiTheme.COL_FG, count);
        tx += ui.width(ui.fonts().smallMedium(), count);
        ui.textCentredY(draw, ui.fonts().small(), tx, y, barH, ImGuiTheme.COL_FG2, selected);
        tx += ui.width(ui.fonts().small(), selected) + m.u(3);
        float sh = m.controlSmallHeight();
        ImGui.setCursorScreenPos(tx, y + (barH - sh) * 0.5f);
        if (w.link("##bar-clear", null, CLEAR, true, sh)) {
            actions.selection().clear();
        }
        tx += w.linkWidth(null, CLEAR) + m.u(3);
        ImGui.setCursorScreenPos(tx, y + m.u(2));
        if (ui.button("##bar-install", Icons.DOWNLOAD, install, Tone.PRIMARY, canInstall)) {
            actions.installTicked();
        }
    }
}
