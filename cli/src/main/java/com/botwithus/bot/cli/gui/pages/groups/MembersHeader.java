package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The line over the members table: its title and counts, and on the right
 * either Add clients or, while rows are ticked, the bulk Stop and Remove.
 * Under it, the last action's notice, if any.
 */
final class MembersHeader {

    private static final float NOTICE_LINE = 1.4f;

    private final GroupWidgets w;
    private final GroupsModel model;
    private final Runnable openAddClients;

    MembersHeader(GroupWidgets widgets, GroupsModel model, Runnable openAddClients) {
        this.w = widgets;
        this.model = model;
        this.openAddClients = openAddClients;
    }

    /** Draws the line and the notice at (x, y), {@code width} wide; returns their height. */
    float render(GroupDetail detail, GroupsPageState state, Map<String, MemberManagement> links, float x, float y,
                 float width) {
        ImGuiTheme.Metrics m = w.m();
        float left = x + m.u(5);
        float right = x + width - m.u(5);
        float top = y + m.u(4);
        float rowH = m.controlSmallHeight();
        float countsEnd = title(detail, links.size(), left, top, rowH);
        GroupId id = detail.group().id();
        List<String> ticked = state.ticks().ticked(id, detail.keys());
        if (ticked.isEmpty()) {
            addButton(right, top, rowH);
        } else {
            bulkBar(detail, state, ticked, Math.max(countsEnd, right - bulkWidth(ticked)), top, rowH);
        }
        float h = m.u(4) + rowH + m.u(2);
        return h + notice(left, y + h, right - left);
    }

    /**
     * "Members", "6 clients · 4 connected" and, when some are, "🤖 2 also
     * managed on its own"; returns where the counts end.
     */
    private float title(GroupDetail detail, int alsoManaged, float x, float y, float h) {
        Controls ui = w.ui();
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont font = ui.fonts().smallMedium();
        ui.textCentredY(draw, font, x, y, h, ImGuiTheme.COL_FG, "Members");
        String counts = GroupText.count(detail.rows().size(), "client") + " · "
                + detail.summary().connected() + " connected";
        float cx = x + ui.width(font, "Members") + w.m().u(2);
        ui.textCentredY(draw, ui.fonts().monoCaption(), cx, y, h, ImGuiTheme.COL_FG2, counts);
        float end = cx + ui.width(ui.fonts().monoCaption(), counts);
        if (alsoManaged > 0) {
            end = alsoManaged(draw, alsoManaged, end, y, h);
        }
        return end + w.m().u(3);
    }

    /** " · 🤖 2 also managed on its own", in the link colour; returns where it ends. */
    private float alsoManaged(ImDrawList draw, int count, float x, float y, float h) {
        Controls ui = w.ui();
        ImFont mono = ui.fonts().monoCaption();
        ImFont cap = ui.fonts().caption();
        ui.textCentredY(draw, mono, x, y, h, ImGuiTheme.COL_FG2, " · ");
        float rx = x + ui.width(mono, " · ");
        ui.textCentredY(draw, cap, rx, y, h, ImGuiTheme.COL_INFO, Icons.ROBOT);
        String text = count + " also managed on its own";
        float tx = rx + ui.width(cap, Icons.ROBOT) + w.m().u(1);
        ui.textCentredY(draw, cap, tx, y, h, ImGuiTheme.COL_INFO, text);
        return tx + ui.width(cap, text);
    }

    private void addButton(float right, float y, float h) {
        Controls ui = w.ui();
        float bw = ui.buttonWidth(GroupWidgets.USER_PLUS, "Add clients", Tone.GHOST);
        ImGui.setCursorScreenPos(right - bw, y);
        if (ui.button("##members-add", GroupWidgets.USER_PLUS, "Add clients", Tone.GHOST, true, h)) {
            openAddClients.run();
        }
    }

    private float bulkWidth(List<String> ticked) {
        Controls ui = w.ui();
        float gap = w.m().u(2);
        return ui.width(ui.fonts().caption(), selectedText(ticked)) + gap
                + ui.buttonWidth(Icons.STOP, "Stop", Tone.GHOST) + gap
                + ui.buttonWidth(GroupWidgets.USER_MINUS, "Remove", Tone.GHOST) + gap
                + ui.buttonWidth(null, "Clear", Tone.GHOST);
    }

    private void bulkBar(GroupDetail detail, GroupsPageState state, List<String> ticked, float x, float y,
                         float h) {
        Controls ui = w.ui();
        float gap = w.m().u(2);
        String text = selectedText(ticked);
        ui.textCentredY(ImGui.getWindowDrawList(), ui.fonts().caption(), x, y, h, ImGuiTheme.COL_FG2, text);
        float bx = x + ui.width(ui.fonts().caption(), text) + gap;
        List<String> stoppable = stoppableUuids(detail, ticked);
        ImGui.setCursorScreenPos(bx, y);
        if (ui.button("##members-stop", Icons.STOP, "Stop", Tone.GHOST, !stoppable.isEmpty(), h)) {
            model.stopMembers(stoppable);
            state.ticks().clear();
        }
        bx += ui.buttonWidth(Icons.STOP, "Stop", Tone.GHOST) + gap;
        ImGui.setCursorScreenPos(bx, y);
        if (ui.button("##members-remove", GroupWidgets.USER_MINUS, "Remove", Tone.GHOST, true, h)) {
            model.removeMembers(detail.group().id(), ticked);
            state.ticks().clear();
        }
        bx += ui.buttonWidth(GroupWidgets.USER_MINUS, "Remove", Tone.GHOST) + gap;
        ImGui.setCursorScreenPos(bx, y);
        if (ui.button("##members-clear", null, "Clear", Tone.GHOST, true, h)) {
            state.ticks().clear();
        }
    }

    private static String selectedText(List<String> ticked) {
        return ticked.size() + " selected";
    }

    /** The ticked members with something to stop: a script running, or a start waiting. */
    static List<String> stoppableUuids(GroupDetail detail, List<String> ticked) {
        return detail.rows().stream()
                .flatMap(row -> switch (row) {
                    case MemberRow.Account account -> Stream.of(account);
                    case MemberRow.Unresolved _ -> Stream.<MemberRow.Account>empty();
                })
                .filter(row -> ticked.contains(row.key()))
                .filter(row -> row.action() == RowAction.STOP || row.action() == RowAction.CANCEL_QUEUED)
                .map(MemberRow.Account::key)
                .toList();
    }

    /** The last action's notice, with a dismiss button; returns its height, 0 when there is none. */
    private float notice(float x, float y, float width) {
        Optional<Notice> current = model.notice();
        if (current.isEmpty()) {
            return 0f;
        }
        ImGuiTheme.Metrics m = w.m();
        Controls ui = w.ui();
        Notice notice = current.get();
        ImFont font = ui.fonts().caption();
        float lineH = font.getFontSize() * NOTICE_LINE;
        float close = m.controlSmallHeight();
        float iconW = ui.width(font, Icons.INFO) + m.u(2);
        List<String> lines = ui.wrap(font, notice.text(), width - m.u(3) * 2f - iconW - close);
        float h = Math.max(close, lines.size() * lineH) + m.u(2) * 2f;
        int fg = notice.isProblem() ? ImGuiTheme.COL_WARN : ImGuiTheme.COL_INFO;
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + h,
                notice.isProblem() ? ImGuiTheme.COL_WARN_SOFT : ImGuiTheme.COL_INFO_SOFT, m.radius());
        ui.text(draw, font, x + m.u(3), y + m.u(2), fg, notice.isProblem() ? Icons.WARNING : Icons.INFO);
        float ty = y + m.u(2);
        for (String line : lines) {
            ui.text(draw, font, x + m.u(3) + iconW, ty, ImGuiTheme.COL_FG, line);
            ty += lineH;
        }
        ImGui.setCursorScreenPos(x + width - m.u(1) - close, y + (h - close) * 0.5f);
        if (w.iconButton("##notice-dismiss", Icons.XMARK, ImGuiTheme.COL_FG2, ImGuiTheme.COL_ELEVATED, "Dismiss")) {
            model.dismissNotice();
        }
        return h + m.u(2);
    }
}
