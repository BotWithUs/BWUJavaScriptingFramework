package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.ManagerSlot;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import java.util.List;
import java.util.Optional;

/**
 * The Assign manager dialog: the management scripts loaded now, the group's
 * current manager marked, and the other groups each already manages; a
 * "Start it now" switch; then Assign. A group has one manager, so assigning
 * another replaces it. ↑↓ move, Enter assigns, Esc closes.
 */
final class AssignManagerDialog {

    private static final String POPUP_ID = "##group-assign-manager";
    private static final float WIDTH_EM = 36f;
    private static final float HEIGHT_EM = 30f;
    private static final float ROW_EM = 3.2f;
    private static final float TILE_EM = 1.733f;
    private static final float LINE = 1.4f;
    private static final String INTRO = "Management scripts from the management folder. One per group. It only"
            + " sees and controls this group's clients.";
    private static final String NONE = "No management scripts are loaded. Put a JAR that implements"
            + " ManagementScript in the management folder and reload it on the Management page.";

    private final GroupWidgets w;
    private final GroupsModel model;
    private GroupId group;
    private int highlighted;
    private boolean isStartNow = true;
    private boolean isOpening;

    AssignManagerDialog(GroupWidgets widgets, GroupsModel model) {
        this.w = widgets;
        this.model = model;
    }

    void open(GroupId id) {
        group = id;
        highlighted = -1;
        isStartNow = true;
        isOpening = true;
    }

    /** Package-private: the dev preview's seam for highlighting a script, as a user would. */
    void highlight(int row) {
        highlighted = row;
    }

    void render(GroupsSnapshot snapshot) {
        boolean isFirst = isOpening;
        isOpening = false;
        if (!w.beginModal(POPUP_ID, WIDTH_EM, HEIGHT_EM, isFirst)) {
            return;
        }
        Optional<ClientGroup> shown = group == null ? Optional.empty() : snapshot.group(group);
        if (shown.isEmpty() || content(shown.get())) {
            GroupWidgets.closeModal();
            group = null;
        }
        GroupWidgets.endModal();
    }

    /** Draws the dialog; returns true when it should close. */
    private boolean content(ClientGroup shown) {
        float x = ImGui.getWindowPosX();
        float y = ImGui.getWindowPosY();
        float width = ImGui.getWindowWidth();
        float height = ImGui.getWindowHeight();
        List<ManagerChoice> choices = model.managers();
        Optional<String> current = shown.manager().map(ManagerSlot::script);
        if (highlighted < 0) {
            highlighted = Math.max(0, current.map(c -> indexOf(choices, c)).orElse(0));
        }
        highlighted = Math.max(0, Math.min(highlighted, choices.size() - 1));
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            return true;
        }
        boolean isEnter = keys(choices.size());
        float headerH = header(shown, x, y, width);
        float footerH = w.m().u(3) * 2f + w.m().controlHeight();
        list(choices, shown, x, y + headerH, width, height - headerH - footerH);
        Optional<ManagerChoice> pick = choices.isEmpty() ? Optional.empty() : Optional.of(choices.get(highlighted));
        boolean canAssign = pick.filter(p -> current.filter(p.script()::equals).isEmpty()).isPresent();
        Footer footer = footer(pick, canAssign, x, y + height - footerH, width);
        if ((footer == Footer.ASSIGN || isEnter) && canAssign) {
            model.assignManager(shown.id(), pick.get().script(), isStartNow);
            return true;
        }
        return footer == Footer.CANCEL || group == null;
    }

    private enum Footer { NONE, ASSIGN, CANCEL }

    private static int indexOf(List<ManagerChoice> choices, String script) {
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).script().equals(script)) {
                return i;
            }
        }
        return 0;
    }

    /** ↑↓ move the highlight; returns true when Enter was pressed. */
    private boolean keys(int count) {
        if (ImGui.isKeyPressed(ImGuiKey.DownArrow)) {
            highlighted = Math.min(count - 1, highlighted + 1);
        }
        if (ImGui.isKeyPressed(ImGuiKey.UpArrow)) {
            highlighted = Math.max(0, highlighted - 1);
        }
        return ImGui.isKeyPressed(ImGuiKey.Enter, false) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter, false);
    }

    private float header(ClientGroup shown, float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        float left = x + m.u(4);
        float inner = width - m.u(4) * 2f;
        String title = shown.manager().isPresent() ? "Change manager" : "Assign a manager";
        if (w.modalTitle("##assign-close", title, " for " + shown.name(), left, y + m.u(4), inner)) {
            group = null;
        }
        Controls ui = w.ui();
        ImFont font = ui.fonts().caption();
        float lineH = font.getFontSize() * LINE;
        float ty = y + m.u(4) + m.controlHeight() + m.u(2);
        for (String line : ui.wrap(font, INTRO, inner)) {
            ui.text(ImGui.getWindowDrawList(), font, left, ty, ImGuiTheme.COL_FG2, line);
            ty += lineH;
        }
        return ty - y + m.u(3);
    }

    private void list(List<ManagerChoice> choices, ClientGroup shown, float x, float y, float width,
                      float height) {
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addLine(x, y + 0.5f, x + width, y + 0.5f, ImGuiTheme.COL_BORDER);
        ImGui.setCursorScreenPos(x, y + 1f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0f, 0f);
        ImGui.beginChild("##assign-list", width, Math.max(1f, height - 1f), false, ImGuiWindowFlags.None);
        if (choices.isEmpty()) {
            Controls ui = w.ui();
            ui.centredParagraph(ImGui.getWindowDrawList(), ui.fonts().small(), x + width * 0.5f,
                    y + w.m().u(6), width - w.m().u(8) * 2f, ui.fonts().small().getFontSize() * LINE,
                    ImGuiTheme.COL_FG2, NONE);
        }
        for (int i = 0; i < choices.size(); i++) {
            option(choices.get(i), shown, i, width);
        }
        ImGui.endChild();
        ImGui.popStyleVar(2);
    }

    private void option(ManagerChoice choice, ClientGroup shown, int index, float width) {
        ImGuiTheme.Metrics m = w.m();
        Controls ui = w.ui();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float h = w.fs() * ROW_EM;
        if (ImGui.invisibleButton("##assign-opt-" + index, width, h)) {
            highlighted = index;
        }
        boolean isOn = index == highlighted;
        ImDrawList draw = ImGui.getWindowDrawList();
        if (isOn || ImGui.isItemHovered()) {
            draw.addRectFilled(x, y, x + width, y + h, isOn ? ImGuiTheme.COL_BG : ImGuiTheme.COL_SURFACE);
        }
        draw.addLine(x, y + h - 0.5f, x + width, y + h - 0.5f, ImGuiTheme.COL_BORDER);
        float tile = w.fs() * TILE_EM;
        ui.iconTile(draw, x + m.u(4), y + (h - tile) * 0.5f, tile, Icons.ROBOT,
                isOn ? ImGuiTheme.COL_INFO : ImGuiTheme.COL_FG2,
                isOn ? ImGuiTheme.COL_INFO_SOFT : ImGuiTheme.COL_SURFACE);
        boolean isCurrent = shown.manager().map(ManagerSlot::script).filter(choice.script()::equals).isPresent();
        List<String> elsewhere = choice.otherGroups().stream().filter(name -> !name.equals(shown.name())).toList();
        optionText(draw, choice, isCurrent, elsewhere, x + m.u(4) + tile + m.u(3), y,
                width - tile - m.u(4) * 2f - m.u(3), h);
    }

    /** @param elsewhere the other groups it manages, besides this one */
    private void optionText(ImDrawList draw, ManagerChoice choice, boolean isCurrent, List<String> elsewhere,
                            float x, float y, float width, float h) {
        Controls ui = w.ui();
        ImFont name = ui.fonts().smallMedium();
        ImFont desc = ui.fonts().caption();
        float nameH = name.getFontSize() * LINE;
        float top = y + (h - nameH - desc.getFontSize() * LINE) * 0.5f;
        ui.textCentredY(draw, name, x, top, nameH, ImGuiTheme.COL_FG, choice.script());
        float cx = x + ui.width(name, choice.script()) + w.m().u(1.5f);
        if (!choice.version().isBlank()) {
            String version = "v" + choice.version();
            ui.textCentredY(draw, ui.fonts().monoCaption(), cx, top, nameH, ImGuiTheme.COL_FG2, version);
            cx += ui.width(ui.fonts().monoCaption(), version) + w.m().u(2);
        }
        float chipY = top + (nameH - w.m().chipHeight()) * 0.5f;
        if (isCurrent) {
            ui.chip(draw, cx, chipY, "current", ImGuiTheme.COL_INFO, ImGuiTheme.COL_INFO_SOFT, 1f);
            cx += ui.chipWidth("current") + w.m().u(1.5f);
        }
        if (!elsewhere.isEmpty()) {
            String also = "also on " + GroupText.join(elsewhere);
            ui.chip(draw, cx, chipY, ui.ellipsize(ui.fonts().captionMedium(), also, Math.max(0f, x + width - cx)),
                    ImGuiTheme.COL_FG2, ImGuiTheme.COL_ELEVATED, 1f);
        }
        String description = choice.description().isBlank() ? "No description." : choice.description();
        ui.text(draw, desc, x, top + nameH, ImGuiTheme.COL_FG2, ui.ellipsize(desc, description, width));
    }

    private Footer footer(Optional<ManagerChoice> pick, boolean canAssign, float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        Controls ui = w.ui();
        ImGui.getWindowDrawList().addLine(x, y + 0.5f, x + width, y + 0.5f, ImGuiTheme.COL_BORDER);
        float by = y + m.u(3);
        ImGui.setCursorScreenPos(x + m.u(4), by);
        if (ui.toggleRow("##assign-start-now", "Start it now", isStartNow, ui.width(ui.fonts().small(),
                "Start it now") + m.u(3) + ui.toggleWidth())) {
            isStartNow = !isStartNow;
        }
        String label = pick.map(p -> "Assign " + p.script()).orElse("Assign");
        float assignW = ui.buttonWidth(Icons.ROBOT, label, Tone.PRIMARY);
        float cancelW = ui.buttonWidth(null, "Cancel", Tone.GHOST);
        float right = x + width - m.u(4);
        ImGui.setCursorScreenPos(right - assignW - m.u(2) - cancelW, by);
        if (ui.button("##assign-cancel", null, "Cancel", Tone.GHOST, true)) {
            return Footer.CANCEL;
        }
        ImGui.setCursorScreenPos(right - assignW, by);
        return ui.button("##assign-go", Icons.ROBOT, label, Tone.PRIMARY, canAssign) ? Footer.ASSIGN : Footer.NONE;
    }
}
