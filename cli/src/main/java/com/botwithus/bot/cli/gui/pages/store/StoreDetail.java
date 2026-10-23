package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.ArrayList;
import java.util.List;

/**
 * The detail pane beside the list: the picked script's name, author, badges,
 * description, the facts that decide whether it can be installed here, and its
 * one action. Draws into the current (scrolling) window.
 */
final class StoreDetail {

    private static final float ICON_EM = 2.667f;
    private static final float LINE_HEIGHT = 1.45f;
    private static final float DESC_LINE = 1.55f;
    private static final String AGENT_V2 = "agent v2";
    private static final String AGENT_V1 = "agent v1";

    private record Fact(String label, String value, int col) {}

    private final StoreWidgets w;
    private final Controls ui;
    private final StoreRowPainter rows;

    StoreDetail(StoreWidgets widgets, StoreRowPainter rows) {
        this.w = widgets;
        this.ui = widgets.ui();
        this.rows = rows;
    }

    /** The prompt shown when the pane is open with nothing picked. */
    void renderEmpty() {
        ImGuiTheme.Metrics m = ui.m();
        ui.text(ImGui.getWindowDrawList(), ui.fonts().caption(), ImGui.getCursorScreenPosX(),
                ImGui.getCursorScreenPosY() + m.u(1), ImGuiTheme.COL_FG2, "Select a script to see its details.");
    }

    /** Draws {@code row}; offers a close button when {@code isClosable}. */
    void render(StoreRow row, StoreActions actions, boolean canInstall, boolean isClosable) {
        ImGuiTheme.Metrics m = ui.m();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float width = ImGui.getContentRegionAvailX();
        float cy = top(row, actions, x, y, width, isClosable) + m.u(4);
        cy = badges(row, x, cy) + m.u(4);
        cy = description(row, x, cy, width) + m.u(4);
        cy = facts(row, x, cy, width) + m.u(5);
        cy = action(row, actions, x, cy, width, canInstall);
        cy = notes(row, x, cy + m.u(2), width);
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, cy - y + m.u(4));
    }

    private float top(StoreRow row, StoreActions actions, float x, float y, float width, boolean isClosable) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float icon = ui.fonts().body().getFontSize() * ICON_EM;
        rows.iconTile(draw, row, x, y, icon, 1f);
        float right = x + width;
        if (isClosable) {
            ImGui.setCursorScreenPos(right - m.controlSmallHeight(), y);
            if (ui.button("##detail-close", Icons.XMARK, "", Tone.ICON, true, m.controlSmallHeight())) {
                actions.closeDetail();
            }
            right -= m.controlSmallHeight() + m.u(1);
        }
        ImGui.setCursorScreenPos(right - w.starSize(), y + (icon - w.starSize()) * 0.5f);
        if (w.star("##detail-star", row.isFavourite())) {
            actions.star(row.id());
        }
        float tx = x + icon + m.u(3);
        float room = right - w.starSize() - m.u(2) - tx;
        ImFont title = ui.fonts().bodyMedium();
        ImFont by = ui.fonts().caption();
        float block = title.getFontSize() * LINE_HEIGHT + by.getFontSize() * LINE_HEIGHT;
        float ty = y + (icon - block) * 0.5f;
        ui.text(draw, title, tx, ty, ImGuiTheme.COL_FG, ui.ellipsize(title, row.name(), room));
        String byline = (row.author().isBlank() ? "" : "by " + row.author() + " · ") + row.category().getDisplayName();
        ui.text(draw, by, tx, ty + title.getFontSize() * LINE_HEIGHT, ImGuiTheme.COL_FG2,
                ui.ellipsize(by, byline, room));
        return y + icon;
    }

    private float badges(StoreRow row, float x, float y) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float gap = ui.m().u(1);
        float bx = x;
        String price = RowPresentation.price(row.pricing());
        if (!price.isEmpty()) {
            rows.priceBadge(draw, row.pricing(), bx, y);
            bx += w.badgeWidth(price) + gap;
        }
        if (row.isOwned()) {
            bx += w.badge(draw, bx, y, "Yours", ImGuiTheme.COL_INFO, ImGuiTheme.COL_INFO_SOFT, 0) + gap;
        }
        if (!row.runsHere()) {
            w.badge(draw, bx, y, "Older agent only", ImGuiTheme.COL_FG2, 0, ImGuiTheme.COL_BORDER);
        }
        boolean hasAny = !price.isEmpty() || row.isOwned() || !row.runsHere();
        return hasAny ? y + w.badgeHeight() : y - ui.m().u(4);
    }

    private float description(StoreRow row, float x, float y, float width) {
        String text = row.description().isBlank() ? row.summary() : row.description();
        if (text.isBlank()) {
            return y - ui.m().u(4);
        }
        ImFont font = ui.fonts().small();
        float lh = font.getFontSize() * DESC_LINE;
        float cy = y;
        for (String paragraph : text.split("\n")) {
            for (String line : ui.wrap(font, paragraph.strip(), width)) {
                ui.text(ImGui.getWindowDrawList(), font, x, cy, ImGuiTheme.COL_FG2, line);
                cy += lh;
            }
        }
        return cy;
    }

    private float facts(StoreRow row, float x, float y, float width) {
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont label = ui.fonts().caption();
        ImFont value = ui.fonts().monoCaption();
        float lh = label.getFontSize() * LINE_HEIGHT + ui.m().u(1);
        float cy = y;
        for (Fact f : factsOf(row)) {
            ui.text(draw, label, x, cy, ImGuiTheme.COL_FG2, f.label());
            float room = width - ui.width(label, f.label()) - ui.m().u(4);
            String shown = ui.ellipsize(value, f.value(), room);
            ui.text(draw, value, x + width - ui.width(value, shown), cy, f.col(), shown);
            cy += lh;
        }
        return cy;
    }

    private static List<Fact> factsOf(StoreRow row) {
        List<Fact> facts = new ArrayList<>();
        if (!row.storeVersion().isBlank()) {
            facts.add(new Fact("Store version", "v" + row.storeVersion(), ImGuiTheme.COL_FG));
        }
        facts.add(new Fact("On this PC", RowPresentation.onThisPc(row), onThisPcColour(row)));
        facts.add(new Fact("Runs on", runsOn(row), ImGuiTheme.COL_FG));
        facts.add(new Fact("Price", RowPresentation.access(row), ImGuiTheme.COL_FG));
        if (!row.scriptClass().isBlank()) {
            String cls = row.scriptClass();
            facts.add(new Fact("Class", cls.substring(cls.lastIndexOf('.') + 1), ImGuiTheme.COL_FG));
        }
        return facts;
    }

    private static int onThisPcColour(StoreRow row) {
        return switch (row.state()) {
            case RowState.UpdateAvailable ignored -> ImGuiTheme.COL_INFO;
            case RowState.Installed ignored -> ImGuiTheme.COL_ACCENT;
            case RowState.NotInstalled ignored -> ImGuiTheme.COL_FG3;
        };
    }

    private static String runsOn(StoreRow row) {
        if (row.runsHere() && row.runsOnOlder()) {
            return AGENT_V2 + " · " + AGENT_V1;
        }
        if (row.runsHere()) {
            return AGENT_V2;
        }
        return row.runsOnOlder() ? AGENT_V1 : "not reported";
    }

    /** The full-width form of the row's own button. Returns the y under it. */
    private float action(StoreRow row, StoreActions actions, float x, float y, float width, boolean canInstall) {
        float h = ui.m().controlHeight();
        ImGui.pushID("detail-" + row.id());
        boolean clicked = switch (RowPresentation.action(row)) {
            case OPEN -> fullButton(x, y, width, Icons.FOLDER_OPEN, "Open in Installed scripts", Tone.GHOST, true);
            case UPDATE -> fullButton(x, y, width, StoreRowPainter.ARROW_UP, "Update", Tone.SOFT, canInstall);
            case INSTALL -> fullButton(x, y, width, Icons.DOWNLOAD, "Install", Tone.GHOST, canInstall);
            case INSTALLING -> fullButton(x, y, width, null, "Installing…", Tone.GHOST, false);
            case CANNOT_INSTALL -> fullButton(x, y, width, null, "Can’t install here", Tone.GHOST, false);
        };
        ImGui.popID();
        if (clicked) {
            actions.act(row);
        }
        return y + h;
    }

    /** A kit button centred under the facts. */
    private boolean fullButton(float x, float y, float width, String icon, String label, Tone tone,
                               boolean enabled) {
        ImGui.setCursorScreenPos(x + (width - ui.buttonWidth(icon, label, tone)) * 0.5f, y);
        return ui.button("##action", icon, label, tone, enabled);
    }

    private float notes(StoreRow row, float x, float y, float width) {
        float cy = y;
        if (!row.runsHere()) {
            cy = note(x, cy, width, "This host runs agent v2. The author needs to publish a v2 build "
                    + "before it can be installed here.");
        }
        if (row.state().wasInstalledBefore() && row.runsHere()) {
            cy = note(x, cy, width, "The Store installed this earlier, but it is not loaded now: Store scripts "
                    + "stay in memory until the host closes. Install it again to use it.");
        }
        if (row.state().isOnThisPc() && !row.state().hasUpdate()) {
            cy = note(x, cy, width, "Start and stop it from Installed scripts or on a client card.");
        }
        return cy;
    }

    private float note(float x, float y, float width, String text) {
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont font = ui.fonts().caption();
        float lh = font.getFontSize() * LINE_HEIGHT;
        ui.text(draw, font, x, y, ImGuiTheme.COL_FG3, Icons.INFO);
        float tx = x + ui.width(font, Icons.INFO) + ui.m().u(1.5f);
        float cy = y;
        for (String line : ui.wrap(font, text, x + width - tx)) {
            ui.text(draw, font, tx, cy, ImGuiTheme.COL_FG2, line);
            cy += lh;
        }
        return cy + ui.m().u(1);
    }
}
