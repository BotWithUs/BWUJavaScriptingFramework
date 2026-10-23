package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.nav.PageId;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.function.Consumer;

/**
 * The pane beside the table for the selected row: who it is (with the full
 * UUID to copy), its game state and "Resume after restart", how its link is
 * doing or how the retrying is going, its history, the scripts and groups on
 * it, and what can be done with it.
 */
final class ConnectionsDetailPane {

    private static final String UNKNOWN_ACCOUNT = "Unknown account";
    private static final double COPIED_FOR_S = 1.5;

    private final ConnectionWidgets w;
    private final Controls ui;
    private final ConnectionsModel model;
    private final Consumer<PageId> navigate;
    private final RowSelection selection;
    private final DetailSections sections;
    private double copiedAt = Double.NEGATIVE_INFINITY;

    ConnectionsDetailPane(ConnectionWidgets widgets, ConnectionsModel model, Consumer<PageId> navigate,
                          RowSelection selection) {
        this.w = widgets;
        this.ui = widgets.ui();
        this.model = model;
        this.navigate = navigate;
        this.selection = selection;
        this.sections = new DetailSections(widgets, model);
    }

    /** The prompt shown with nothing selected. */
    void renderEmpty() {
        ImGuiTheme.Metrics m = ui.m();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        w.note(ImGui.getWindowDrawList(), x, y, ImGui.getContentRegionAvailX(),
                "Select a connection to see its details.");
        ImGui.dummy(0f, m.u(4));
    }

    /**
     * The pane for {@code detail}, drawn from the cursor down.
     *
     * @param isClosable draws a close button: the table is narrow and the pane covers part of it
     */
    void render(ConnectionDetail detail, boolean isAutoConnect, boolean isClosable) {
        float x = ImGui.getCursorScreenPosX();
        float width = ImGui.getContentRegionAvailX();
        float y = top(detail.row(), x, ImGui.getCursorScreenPosY(), width, isClosable);
        y = rule(y, x, width);
        y = identity(detail, x, y, width);
        y = rule(y, x, width);
        y = sections.link(detail, isAutoConnect, x, y, width);
        if (!detail.history().isEmpty()) {
            y = rule(y, x, width);
            y = sections.history(detail.history(), x, y, width);
        }
        y = rule(y, x, width);
        y = links(detail, x, y, width);
        y = rule(y, x, width);
        y = sections.actions(detail.row(), x, y, width);
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, ui.m().u(4));
    }

    private float top(ConnectionRow row, float x, float y, float width, boolean isClosable) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont title = ui.fonts().bodyMedium();
        float closeW = isClosable ? w.iconButtonSize() + m.u(2) : 0f;
        String name = row.account().orElse(UNKNOWN_ACCOUNT);
        ui.text(draw, title, x, y, row.account().isPresent() ? ImGuiTheme.COL_FG : ImGuiTheme.COL_FG3,
                ui.ellipsize(title, name, width - closeW));
        if (isClosable) {
            ImGui.setCursorScreenPos(x + width - w.iconButtonSize(), y - m.u(1));
            if (w.iconButton("##detail-close", Icons.XMARK, false, false, "Close")) {
                selection.clear();
            }
        }
        float cy = y + w.lineHeight(title);
        ImFont mono = ui.fonts().monoCaption();
        String where = whereOf(row);
        ui.text(draw, mono, x, cy, ImGuiTheme.COL_FG2, ui.ellipsize(mono, where, width));
        cy += w.lineHeight(mono) + m.u(1);
        w.linkState(draw, x, cy, m.controlSmallHeight(), row.link());
        return cy + m.controlSmallHeight() + m.u(2);
    }

    /** The line under the name: the pipe and world, or for a closed client the world it was last in. */
    private static String whereOf(ConnectionRow row) {
        String world = row.world().isPresent() ? "World " + row.world().getAsInt() : "";
        if (row.pipe().isPresent()) {
            return world.isEmpty() ? row.pipe().get() : row.pipe().get() + " · " + world;
        }
        return world.isEmpty() ? "No pipe" : "Last seen in " + world;
    }

    private float identity(ConnectionDetail detail, float x, float y, float width) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        ConnectionRow row = detail.row();
        float cy = w.sectionTitle(draw, x, y, "Identity");
        if (row.accountUuid().isPresent()) {
            cy = uuidBox(row, x, cy, width);
        } else {
            cy = w.note(draw, x, cy, width, row.group() == RowGroup.FOUND
                    ? "The account UUID is read when the host connects to this pipe."
                    : "This client reports no account UUID, so it is known by its pipe and "
                    + "is not kept once it closes.") + m.u(2);
        }
        cy = w.keyValue(draw, x, cy, width, "Game", ConnectionText.gameLong(row.game()), ImGuiTheme.COL_FG);
        return resumeRow(detail, x, cy, width) + m.u(2);
    }

    private float uuidBox(ConnectionRow row, float x, float y, float width) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float h = m.controlHeight();
        draw.addRectFilled(x, y, x + width, y + h, ImGuiTheme.COL_BG, m.radius());
        draw.addRect(x + 0.5f, y + 0.5f, x + width - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER, m.radius());
        float button = w.iconButtonSize();
        boolean isCopied = ImGui.getTime() - copiedAt < COPIED_FOR_S;
        String copied = isCopied ? "Copied" : "";
        ImFont mono = ui.fonts().monoCaption();
        float textW = width - m.u(3) - button - m.u(1) - (isCopied ? ui.width(ui.fonts().caption(), copied) : 0f);
        ui.textCentredY(draw, mono, x + m.u(3), y, h, ImGuiTheme.COL_FG,
                ui.ellipsize(mono, row.accountUuid().orElse(""), textW));
        if (isCopied) {
            ui.textCentredY(draw, ui.fonts().caption(), x + width - button - m.u(1) - ui.width(ui.fonts().caption(),
                    copied), y, h, ImGuiTheme.COL_ACCENT, copied);
        }
        ImGui.setCursorScreenPos(x + width - button - (h - button) * 0.5f, y + (h - button) * 0.5f);
        if (w.iconButton("##copy-uuid", Icons.COPY, false, false,
                "Copy the account UUID. Scripts resume by this, not by pipe.")) {
            model.copyUuid(row);
            copiedAt = ImGui.getTime();
        }
        return y + h + m.u(3);
    }

    private float resumeRow(ConnectionDetail detail, float x, float y, float width) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float h = m.controlSmallHeight();
        ui.textCentredY(draw, ui.fonts().caption(), x, y, h, ImGuiTheme.COL_FG2, "Resume after restart");
        if (detail.resumeAfterRestart().isEmpty()) {
            ImFont mono = ui.fonts().monoCaption();
            ui.textCentredY(draw, mono, x + width - ui.width(mono, ConnectionText.NONE), y, h, ImGuiTheme.COL_FG3,
                    ConnectionText.NONE);
            return y + h;
        }
        boolean isOn = detail.resumeAfterRestart().get();
        ImGui.setCursorScreenPos(x + width - w.switchWidth(), y);
        if (w.switchToggle("##resume", isOn, true, h)) {
            model.setResumeAfterRestart(detail.row(), !isOn);
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Start this account's scripts again when it connects, on any pipe");
        }
        return y + h;
    }

    /** The scripts and groups on this client, as chips that open their pages. */
    private float links(ConnectionDetail detail, float x, float y, float width) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float cy = w.sectionTitle(draw, x, y, "Scripts on this client");
        if (detail.scripts().isEmpty()) {
            cy = w.note(draw, x, cy, width, "None yet.");
        } else {
            ChipFlow flow = new ChipFlow(x, cy, width, w.chipHeight(), m.u(1));
            for (ScriptChip script : detail.scripts()) {
                ImGui.setCursorScreenPos(flow.place(w.chipWidth(script.name())), flow.y());
                if (w.chip("##script-" + script.name(), null, ConnectionWidgets.colour(script.tone()), script.name(),
                        "Open Installed scripts")) {
                    navigate.accept(PageId.INSTALLED);
                }
            }
            cy = flow.bottom();
        }
        if (!detail.groups().isEmpty()) {
            cy = w.sectionTitle(draw, x, cy + m.u(3), "Groups");
            ChipFlow flow = new ChipFlow(x, cy, width, w.chipHeight(), m.u(1));
            for (String group : detail.groups()) {
                ImGui.setCursorScreenPos(flow.place(w.chipWidth(group)), flow.y());
                if (w.chip("##group-" + group, Icons.LAYER_GROUP, ImGuiTheme.COL_FG2, group, "Open Groups")) {
                    navigate.accept(PageId.GROUPS);
                }
            }
            cy = flow.bottom();
        }
        return cy + m.u(4);
    }

    /** A full-width hairline under a section, with the section padding around it. Returns the y after it. */
    private float rule(float y, float x, float width) {
        ImGuiTheme.Metrics m = ui.m();
        float ly = y + m.u(1);
        ImGui.getWindowDrawList().addLine(x - m.u(4), ly, x + width + m.u(4), ly, ImGuiTheme.COL_BORDER,
                m.hairline());
        return ly + m.u(4);
    }

    /** Lays chips left to right, wrapping at the pane's edge. */
    private static final class ChipFlow {
        private final float left;
        private final float right;
        private final float height;
        private final float gap;
        private float x;
        private float y;

        ChipFlow(float left, float top, float width, float height, float gap) {
            this.left = left;
            this.right = left + width;
            this.height = height;
            this.gap = gap;
            this.x = left;
            this.y = top;
        }

        /** Where a chip {@code width} wide goes; moves to the next line when it would not fit. */
        float place(float width) {
            if (x > left && x + width > right) {
                x = left;
                y += height + gap;
            }
            float at = x;
            x += width + gap;
            return at;
        }

        float y() {
            return y;
        }

        float bottom() {
            return y + height;
        }
    }
}
