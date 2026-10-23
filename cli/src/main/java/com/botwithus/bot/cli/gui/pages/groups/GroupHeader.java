package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.ManagerSlot;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.pages.groups.GroupsPageState.Confirm;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiMouseButton;

/**
 * The selected group's name and description, with Start script, Stop all,
 * rename and delete on the right. Stop all and delete ask first, inline, in
 * place of the buttons; rename edits the name where it is.
 */
final class GroupHeader {

    private static final float TITLE_LINE = 1.2f;
    private static final float DESC_LINE = 1.4f;
    private static final float RENAME_WIDTH_EM = 17.33f;
    private static final float DESC_GAP_PX = 2f;

    private final GroupWidgets w;
    private final GroupsModel model;
    private final Runnable openStartScript;

    GroupHeader(GroupWidgets widgets, GroupsModel model, Runnable openStartScript) {
        this.w = widgets;
        this.model = model;
        this.openStartScript = openStartScript;
    }

    /** Draws the header at (x, y), {@code width} wide; returns its height. */
    float render(GroupDetail detail, GroupsPageState state, float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        float top = y + m.u(4);
        float left = x + m.u(5);
        float right = x + width - m.u(5);
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false) && state.confirm() != Confirm.NONE) {
            state.ask(Confirm.NONE);
        }
        float actionsW = state.confirm() == Confirm.NONE
                ? buttons(detail, state, right, top)
                : confirm(detail, state, left, right, top);
        float textW = Math.max(0f, right - left - actionsW - m.u(3));
        float textH = title(detail.group(), state, left, top, textW);
        return m.u(4) + Math.max(textH, m.controlHeight()) + m.u(3);
    }

    private float title(ClientGroup group, GroupsPageState state, float x, float y, float width) {
        Controls ui = w.ui();
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont title = ui.fonts().titleMedium();
        float titleH = title.getFontSize() * TITLE_LINE;
        if (state.isRenaming()) {
            rename(group, state, x, y, Math.min(width, w.fs() * RENAME_WIDTH_EM));
            titleH = Math.max(titleH, w.m().controlHeight());
        } else {
            ui.textCentredY(draw, title, x, y, titleH, ImGuiTheme.COL_FG, ui.ellipsize(title, group.name(), width));
        }
        ImFont small = ui.fonts().small();
        float descY = y + titleH + DESC_GAP_PX;
        String desc = group.description().orElse("No description");
        int col = group.description().isPresent() ? ImGuiTheme.COL_FG2 : ImGuiTheme.COL_FG3;
        ui.text(draw, small, x, descY, col, ui.ellipsize(small, desc, width));
        return titleH + DESC_GAP_PX + small.getFontSize() * DESC_LINE;
    }

    /** The inline name field: Enter or a click elsewhere saves, Esc puts the old name back. */
    private void rename(ClientGroup group, GroupsPageState state, float x, float y, float width) {
        ImGui.setCursorScreenPos(x, y);
        boolean isFocusing = state.takeRenameFocus();
        if (isFocusing) {
            ImGui.setKeyboardFocusHere();
        }
        w.ui().textField("##group-rename", state.renameBuffer(), "Group name", false, width);
        boolean isOutside = ImGui.isMouseClicked(ImGuiMouseButton.Left)
                && !ImGui.isMouseHoveringRect(x, y, x + width, y + w.m().controlHeight());
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            state.stopRename();
        } else if (!isFocusing && (ImGui.isKeyPressed(ImGuiKey.Enter, false) || isOutside)) {
            model.rename(group.id(), state.renameBuffer().get());
            state.stopRename();
        }
    }

    /** Start script, Stop all, rename and delete, right-aligned to {@code right}; returns their width. */
    private float buttons(GroupDetail detail, GroupsPageState state, float right, float top) {
        ImGuiTheme.Metrics m = w.m();
        Controls ui = w.ui();
        float startW = ui.buttonWidth(Icons.PLAY, "Start script", Tone.PRIMARY);
        float stopW = ui.buttonWidth(Icons.STOP, "Stop all", Tone.STOP);
        float iconW = m.controlHeight();
        float total = startW + stopW + iconW * 2f + m.u(2) * 3f;
        float x = right - total;
        ImGui.setCursorScreenPos(x, top);
        boolean hasMembers = !detail.rows().isEmpty();
        if (ui.button("##group-start", Icons.PLAY, "Start script", Tone.PRIMARY, hasMembers)) {
            openStartScript.run();
        }
        ImGui.setCursorScreenPos(x + startW + m.u(2), top);
        if (ui.button("##group-stop", Icons.STOP, "Stop all", Tone.STOP, detail.summary().canStopAll())) {
            state.ask(Confirm.STOP_ALL);
        }
        ImGui.setCursorScreenPos(x + startW + stopW + m.u(2) * 2f, top);
        if (ui.button("##group-rename-btn", GroupWidgets.PEN, "", Tone.ICON, true)) {
            state.startRename(detail.group().name());
        }
        ImGui.setCursorScreenPos(x + startW + stopW + iconW + m.u(2) * 3f, top);
        if (ui.button("##group-delete", Icons.TRASH, "", Tone.ICON, true)) {
            state.ask(Confirm.DELETE);
        }
        return total;
    }

    /** The inline question in place of the buttons; returns its width. */
    private float confirm(GroupDetail detail, GroupsPageState state, float left, float right, float top) {
        ImGuiTheme.Metrics m = w.m();
        Controls ui = w.ui();
        boolean isDelete = state.confirm() == Confirm.DELETE;
        String yes = isDelete ? "Delete group" : "Stop all";
        float h = m.controlHeight();
        float small = m.controlSmallHeight();
        float yesW = ui.buttonWidth(null, yes, Tone.STOP);
        float noW = ui.buttonWidth(null, "Cancel", Tone.GHOST);
        String question = isDelete ? deleteQuestion(detail.group()) : stopQuestion(detail);
        float maxText = right - left - yesW - noW - m.u(3) - m.u(2) * 2f - m.u(1);
        String text = ui.ellipsize(ui.fonts().small(), question, Math.max(0f, maxText));
        float total = m.u(3) + ui.width(ui.fonts().small(), text) + m.u(2) + yesW + m.u(1) + noW + m.u(1);
        float x = right - total;
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, top, right, top + h, ImGuiTheme.COL_DANGER_SOFT, m.radius());
        ui.textCentredY(draw, ui.fonts().small(), x + m.u(3), top, h, ImGuiTheme.COL_FG, text);
        float bx = right - m.u(1) - noW - m.u(1) - yesW;
        ImGui.setCursorScreenPos(bx, top + (h - small) * 0.5f);
        if (ui.button("##group-confirm-yes", null, yes, Tone.STOP, true, small)) {
            act(detail, isDelete);
            state.ask(Confirm.NONE);
        }
        ImGui.setCursorScreenPos(bx + yesW + m.u(1), top + (h - small) * 0.5f);
        if (ui.button("##group-confirm-no", null, "Cancel", Tone.GHOST, true, small)) {
            state.ask(Confirm.NONE);
        }
        return total;
    }

    private void act(GroupDetail detail, boolean isDelete) {
        if (isDelete) {
            model.delete(detail.group().id());
        } else {
            model.stopAll(detail.group().id());
        }
    }

    static String deleteQuestion(ClientGroup group) {
        return "Delete " + group.name() + "? Its clients keep running.";
    }

    /** "Stop 3 scripts in Woodcutters? 1 queued start is cancelled. The manager pauses too." */
    static String stopQuestion(GroupDetail detail) {
        GroupSummary summary = detail.summary();
        StringBuilder text = new StringBuilder("Stop ");
        text.append(GroupText.count(summary.stoppable(), "script")).append(" in ").append(detail.group().name())
                .append('?');
        if (summary.queued() > 0) {
            text.append(' ').append(GroupText.count(summary.queued(), "queued start"))
                    .append(summary.queued() == 1 ? " is" : " are").append(" cancelled.");
        }
        if (detail.group().manager().filter(ManagerSlot::shouldRun).isPresent()) {
            text.append(" The manager pauses too.");
        }
        return text.toString();
    }
}
