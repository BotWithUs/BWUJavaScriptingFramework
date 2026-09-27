package com.botwithus.bot.cli.gui.preview;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.nav.NavBadge;
import com.botwithus.bot.cli.gui.nav.Page;
import com.botwithus.bot.cli.gui.nav.PageId;
import com.botwithus.bot.cli.gui.nav.SecondLine;

import imgui.ImDrawList;
import imgui.ImGui;

import java.util.Optional;

/**
 * DEV ONLY. A page with a fixed sidebar entry and a plain body naming itself,
 * so the preview can show every sidebar state without a live host behind it.
 */
final class FixturePage implements Page {

    private final PageId id;
    private final Controls ui;
    private final Optional<SecondLine> secondLine;
    private final Optional<NavBadge> badge;

    FixturePage(PageId id, Controls ui, Optional<SecondLine> secondLine, Optional<NavBadge> badge) {
        this.id = id;
        this.ui = ui;
        this.secondLine = secondLine;
        this.badge = badge;
    }

    @Override
    public PageId id() {
        return id;
    }

    @Override
    public Optional<SecondLine> secondLine() {
        return secondLine;
    }

    @Override
    public Optional<NavBadge> badge() {
        return badge;
    }

    @Override
    public void render() {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getWindowPosX() + m.u(6);
        float y = ImGui.getWindowPosY() + m.u(5);
        ui.text(draw, ui.fonts().titleMedium(), x, y, ImGuiTheme.COL_FG, id.label());
        ui.text(draw, ui.fonts().small(), x, y + ui.fonts().titleMedium().getFontSize() + m.u(2),
                ImGuiTheme.COL_FG2, "Fixture page. The real one reads the live host.");
    }
}
