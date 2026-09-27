package com.botwithus.bot.cli.gui.pages;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Segment;
import com.botwithus.bot.cli.gui.GuiPanel;
import com.botwithus.bot.cli.gui.nav.Page;
import com.botwithus.bot.cli.gui.nav.PageId;
import com.botwithus.bot.cli.gui.nav.SecondLine;

import imgui.ImGui;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiWindowFlags;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A stand-in page that hosts one or more of the pre-redesign panels until the
 * redesigned page replaces it. With several panels it shows a tab strip over
 * them (the interim Dashboard holds Console, Logs and Diagnostics this way).
 *
 * <p>The panels were laid out inside the old main window's padding and draw with
 * the atlas default font, so this gives them a padded, scrolling region and
 * pushes no font: they render exactly as they did in the old shell.</p>
 */
public final class LegacyPanelPage implements Page {

    private final PageId id;
    private final Controls ui;
    private final CliContext ctx;
    private final List<GuiPanel> panels;
    private final List<Segment> tabs;
    private final Supplier<Optional<SecondLine>> secondLine;
    private int shown;

    /**
     * @param panels     shown as tabs, in this order; the first is shown to start with
     * @param secondLine the sidebar's second line, read every frame the sidebar is drawn
     */
    public LegacyPanelPage(PageId id, Controls ui, CliContext ctx, List<GuiPanel> panels,
                           Supplier<Optional<SecondLine>> secondLine) {
        if (panels.isEmpty()) {
            throw new IllegalArgumentException("A page needs at least one panel: " + id);
        }
        this.id = id;
        this.ui = ui;
        this.ctx = ctx;
        this.panels = List.copyOf(panels);
        this.tabs = this.panels.stream().map(p -> Segment.of(p.title())).toList();
        this.secondLine = secondLine;
    }

    /** A page hosting a single panel, with no second line. */
    public static LegacyPanelPage of(PageId id, Controls ui, CliContext ctx, GuiPanel panel) {
        return new LegacyPanelPage(id, ui, ctx, List.of(panel), Optional::empty);
    }

    @Override
    public PageId id() {
        return id;
    }

    @Override
    public Optional<SecondLine> secondLine() {
        return secondLine.get();
    }

    /** Brings {@code panel}'s tab to the front; does nothing if this page does not host it. */
    public void show(GuiPanel panel) {
        int index = panels.indexOf(panel);
        if (index >= 0) {
            shown = index;
        }
    }

    /**
     * The same two regions the old shell gave a panel: a padded frame that never
     * scrolls, and inside it an unpadded region that does. The tab strip sits in
     * the frame, above the scrolling part.
     */
    @Override
    public void render() {
        ImGui.beginChild("##page-" + id.name(), 0f, 0f, ImGuiChildFlags.AlwaysUseWindowPadding,
                ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        if (panels.size() > 1) {
            int clicked = ui.segmented("##tabs-" + id.name(), tabs, shown, 0f, ui.m().controlSmallHeight());
            if (clicked >= 0) {
                shown = clicked;
            }
        }
        ImGui.beginChild("##content", 0f, 0f, false);
        ImGui.spacing();
        panels.get(shown).render(ctx);
        ImGui.endChild();
        ImGui.endChild();
    }
}
