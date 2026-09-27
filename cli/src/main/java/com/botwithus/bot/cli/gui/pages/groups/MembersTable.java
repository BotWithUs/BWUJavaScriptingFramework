package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import java.util.List;
import java.util.function.Function;

/**
 * The members table: a header row with a select-all box, then one row per
 * member, in a region of its own that scrolls when the group is long.
 */
final class MembersTable {

    private static final float ROW_EM = 3.2f;
    private static final float HEAD_EM = 2.133f;
    private static final float EMPTY_EM = 8f;

    private final GroupWidgets w;
    private final MemberRowPainter painter;
    private final Runnable openAddClients;

    MembersTable(GroupWidgets widgets, GroupsModel model, Runnable openAddClients) {
        this.w = widgets;
        this.painter = new MemberRowPainter(widgets, model);
        this.openAddClients = openAddClients;
    }

    /**
     * Draws the table at (x, y) in a scrolling region {@code width} by {@code height}.
     *
     * @param icons the icon to draw for a script, by name
     */
    void render(GroupDetail detail, GroupsPageState state, Function<String, String> icons, float x, float y,
                float width, float height) {
        ImGui.setCursorScreenPos(x, y);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.beginChild("##group-members", width, Math.max(1f, height), false, ImGuiWindowFlags.None);
        ImGui.popStyleVar();
        ImGuiTheme.Metrics m = w.m();
        float left = ImGui.getCursorScreenPosX() + m.u(5);
        float tableW = ImGui.getContentRegionAvailX() - m.u(5) * 2f;
        float top = ImGui.getCursorScreenPosY();
        if (detail.rows().isEmpty()) {
            empty(left, top, tableW);
        } else {
            table(detail, state, icons, left, top, tableW);
        }
        ImGui.endChild();
    }

    private void table(GroupDetail detail, GroupsPageState state, Function<String, String> icons, float x,
                       float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        float headH = w.fs() * HEAD_EM;
        float rowH = w.fs() * ROW_EM;
        float h = headH + rowH * detail.rows().size();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + h, ImGuiTheme.COL_SURFACE, m.radiusLarge());
        MemberColumns cols = MemberColumns.of(x, width, w.fs(), m.u(4), m.u(3), m.u(3));
        head(detail, state, cols, y, headH, width);
        float ry = y + headH;
        GroupId id = detail.group().id();
        for (int i = 0; i < detail.rows().size(); i++) {
            MemberRow row = detail.rows().get(i);
            draw.addLine(x + 1f, ry + 0.5f, x + width - 1f, ry + 0.5f, ImGuiTheme.COL_BORDER);
            painter.paint(row, id, state.ticks(), cols, icons, x, ry, width, rowH);
            ry += rowH;
        }
        draw.addRect(x + 0.5f, y + 0.5f, x + width - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER, m.radiusLarge());
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, h + m.u(5));
    }

    private void head(GroupDetail detail, GroupsPageState state, MemberColumns cols, float y, float h,
                      float width) {
        GroupId id = detail.group().id();
        List<String> keys = detail.keys();
        boolean isAll = state.ticks().ticked(id, keys).size() == keys.size();
        if (w.checkbox("##members-all", cols.check(), y, h, isAll, true)) {
            state.ticks().setAll(id, keys, !isAll);
        }
        Controls ui = w.ui();
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont font = ui.fonts().captionMedium();
        ui.textCentredY(draw, font, cols.account(), y, h, ImGuiTheme.COL_FG2, "Account");
        if (cols.isWide()) {
            ui.textCentredY(draw, font, cols.world(), y, h, ImGuiTheme.COL_FG2, "World");
            String loop = "Loop";
            ui.textCentredY(draw, font, cols.actions() - cols.gap() - ui.width(font, loop), y, h,
                    ImGuiTheme.COL_FG2, loop);
        }
        ui.textCentredY(draw, font, cols.link(), y, h, ImGuiTheme.COL_FG2, "Link");
        ui.textCentredY(draw, font, cols.script(), y, h, ImGuiTheme.COL_FG2, "Script");
    }

    private void empty(float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        Controls ui = w.ui();
        float h = w.fs() * EMPTY_EM;
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + h, ImGuiTheme.COL_SURFACE, m.radiusLarge());
        draw.addRect(x + 0.5f, y + 0.5f, x + width - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER, m.radiusLarge());
        ImFont font = ui.fonts().small();
        String text = "This group has no clients yet.";
        float textY = y + (h - font.getFontSize() - m.u(3) - m.controlHeight()) * 0.5f;
        ui.text(draw, font, x + (width - ui.width(font, text)) * 0.5f, textY, ImGuiTheme.COL_FG2, text);
        float bw = ui.buttonWidth(GroupWidgets.USER_PLUS, "Add clients", Tone.GHOST);
        ImGui.setCursorScreenPos(x + (width - bw) * 0.5f, textY + font.getFontSize() + m.u(3));
        if (ui.button("##members-add-empty", GroupWidgets.USER_PLUS, "Add clients", Tone.GHOST, true)) {
            openAddClients.run();
        }
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, h);
    }
}
