package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The left-hand list of groups: a header with New group, one item per group
 * (name, robot when managed, member count, one dot per member and "N of M
 * running"), and a footer counting the clients in no group.
 */
final class GroupListPane {

    private static final float LINE = 1.35f;
    private static final float DOT_GAP_PX = 3f;

    private final GroupWidgets w;

    GroupListPane(GroupWidgets widgets) {
        this.w = widgets;
    }

    /**
     * Draws the pane at the cursor, {@code width} by {@code height}.
     *
     * @param ungrouped how many known clients are in no group
     */
    void render(List<GroupListItem> items, Optional<GroupId> selected, int ungrouped, float width, float height,
                Consumer<GroupId> onSelect, Runnable onNewGroup) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addLine(x + width - 0.5f, y, x + width - 0.5f, y + height, ImGuiTheme.COL_BORDER);
        float headerH = header(x, y, width - 1f, onNewGroup);
        float footerH = footerHeight(ungrouped, width);
        ImGui.setCursorScreenPos(x, y + headerH);
        items(items, selected, width - 1f, height - headerH - footerH, onSelect);
        footer(ungrouped, x, y + height - footerH, width - 1f);
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, height);
    }

    private float header(float x, float y, float width, Runnable onNewGroup) {
        ImGuiTheme.Metrics m = w.m();
        Controls ui = w.ui();
        float rowH = m.controlSmallHeight();
        float top = y + m.u(4);
        ui.textCentredY(ImGui.getWindowDrawList(), ui.fonts().titleMedium(), x + m.u(4), top, rowH,
                ImGuiTheme.COL_FG, "Groups");
        float bw = ui.buttonWidth(Icons.PLUS, "New group", Tone.GHOST);
        ImGui.setCursorScreenPos(x + width - m.u(3) - bw, top);
        if (ui.button("##groups-new", Icons.PLUS, "New group", Tone.GHOST, true, rowH)) {
            onNewGroup.run();
        }
        return m.u(4) + rowH + m.u(3);
    }

    private void items(List<GroupListItem> items, Optional<GroupId> selected, float width, float height,
                       Consumer<GroupId> onSelect) {
        ImGuiTheme.Metrics m = w.m();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, m.u(2), 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0f, 2f);
        ImGui.beginChild("##group-list", width, height, false, ImGuiWindowFlags.AlwaysUseWindowPadding);
        if (items.isEmpty()) {
            w.ui().text(ImGui.getWindowDrawList(), w.ui().fonts().caption(), ImGui.getCursorScreenPosX() + m.u(3),
                    ImGui.getCursorScreenPosY() + m.u(2), ImGuiTheme.COL_FG2, "No groups yet.");
        }
        for (GroupListItem item : items) {
            if (item(item, selected.filter(item.id()::equals).isPresent())) {
                onSelect.accept(item.id());
            }
        }
        ImGui.endChild();
        ImGui.popStyleVar(2);
    }

    private float itemHeight() {
        ImGuiTheme.Metrics m = w.m();
        float name = w.ui().fonts().smallMedium().getFontSize() * LINE;
        float sum = w.ui().fonts().caption().getFontSize() * LINE;
        return m.u(2) * 2f + name + m.u(1) + sum;
    }

    private boolean item(GroupListItem item, boolean isSelected) {
        ImGuiTheme.Metrics m = w.m();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float iw = ImGui.getContentRegionAvailX();
        float ih = itemHeight();
        boolean clicked = ImGui.invisibleButton("##group-" + item.id(), iw, ih);
        boolean isHovered = ImGui.isItemHovered();
        ImDrawList draw = ImGui.getWindowDrawList();
        if (isSelected || isHovered) {
            draw.addRectFilled(x, y, x + iw, y + ih, ImGuiTheme.COL_ELEVATED, m.radius());
        }
        if (isSelected) {
            draw.addRect(x + 0.5f, y + 0.5f, x + iw - 0.5f, y + ih - 0.5f, ImGuiTheme.COL_BORDER_HOVER, m.radius());
        }
        float nameH = w.ui().fonts().smallMedium().getFontSize() * LINE;
        nameLine(draw, item, x + m.u(3), y + m.u(2), iw - m.u(3) * 2f, nameH);
        summaryLine(draw, item, x + m.u(3), y + m.u(2) + nameH + m.u(1), iw - m.u(3) * 2f);
        return clicked;
    }

    private void nameLine(ImDrawList draw, GroupListItem item, float x, float y, float width, float h) {
        Controls ui = w.ui();
        ImFont mono = ui.fonts().monoCaption();
        String count = Integer.toString(item.size());
        float countW = ui.width(mono, count);
        ui.textCentredY(draw, mono, x + width - countW, y, h, ImGuiTheme.COL_FG2, count);
        ImFont font = ui.fonts().smallMedium();
        float robotW = item.manager().isPresent() ? ui.width(ui.fonts().caption(), Icons.ROBOT) + w.m().u(2) : 0f;
        String name = ui.ellipsize(font, item.name(), width - countW - robotW - w.m().u(2));
        ui.textCentredY(draw, font, x, y, h, ImGuiTheme.COL_FG, name);
        if (item.manager().isPresent()) {
            float rx = x + ui.width(font, name) + w.m().u(1.5f);
            ui.textCentredY(draw, ui.fonts().caption(), rx, y, h, ImGuiTheme.COL_INFO, Icons.ROBOT);
            if (ImGui.isMouseHoveringRect(rx, y, rx + robotW, y + h)) {
                w.tooltip("Managed by " + item.manager().get());
            }
        }
    }

    private void summaryLine(ImDrawList draw, GroupListItem item, float x, float y, float width) {
        ImFont font = w.ui().fonts().caption();
        float h = font.getFontSize() * LINE;
        float step = w.m().dot() + DOT_GAP_PX;
        float cx = x + w.m().dot() * 0.5f;
        for (MemberHealth dot : item.dots()) {
            if (cx > x + width * 0.5f) {
                break;
            }
            w.dot(draw, cx, y + h * 0.5f, dot);
            cx += step;
        }
        float tx = item.dots().isEmpty() ? x : cx - w.m().dot() * 0.5f + w.m().u(2) - DOT_GAP_PX;
        w.ui().textCentredY(draw, font, tx, y, h, ImGuiTheme.COL_FG2,
                w.ui().ellipsize(font, item.runningText(), x + width - tx));
    }

    private float footerHeight(int ungrouped, float width) {
        ImGuiTheme.Metrics m = w.m();
        ImFont font = w.ui().fonts().caption();
        int lines = w.ui().wrap(font, footerText(ungrouped), width - m.u(4) * 2f).size();
        return m.u(3) * 2f + lines * font.getFontSize() * LINE;
    }

    private void footer(int ungrouped, float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addLine(x, y + 0.5f, x + width, y + 0.5f, ImGuiTheme.COL_BORDER);
        ImFont font = w.ui().fonts().caption();
        float lineH = font.getFontSize() * LINE;
        float ty = y + m.u(3);
        List<String> lines = w.ui().wrap(font, footerText(ungrouped), width - m.u(4) * 2f);
        for (String line : lines) {
            w.ui().text(draw, font, x + m.u(4), ty, ImGuiTheme.COL_FG2, line);
            ty += lineH;
        }
        w.ui().text(draw, font, x + m.u(4), y + m.u(3), ImGuiTheme.COL_FG,
                Integer.toString(ungrouped));
    }

    private static String footerText(int ungrouped) {
        String clients = ungrouped == 1 ? " client isn't in any group." : " clients aren't in any group.";
        return ungrouped + clients + " A client can be in more than one.";
    }
}
