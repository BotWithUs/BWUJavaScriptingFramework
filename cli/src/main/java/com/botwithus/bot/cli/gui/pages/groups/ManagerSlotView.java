package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * The group's manager slot: the management script that manages the group,
 * how it is doing and its latest action, with Settings, Start and Change; or,
 * with none, a dashed slot with Assign manager.
 */
final class ManagerSlotView {

    private static final float ICON_EM = 2.267f;
    private static final String KEY = "MANAGER";
    private static final String EMPTY_LINE = "A management script can start, stop and schedule scripts on this"
            + " group's clients by itself.";

    private final GroupWidgets w;
    private final GroupsModel model;
    private final Consumer<GroupId> openAssign;

    /** @param openAssign opens the Assign manager dialog on a group */
    ManagerSlotView(GroupWidgets widgets, GroupsModel model, Consumer<GroupId> openAssign) {
        this.w = widgets;
        this.model = model;
        this.openAssign = openAssign;
    }

    /** Draws the slot at (x, y), {@code width} wide; returns its height. */
    float render(GroupDetail detail, float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        float icon = w.fs() * ICON_EM;
        float h = m.u(3) * 2f + icon;
        GroupId id = detail.group().id();
        Optional<ManagerInfo> info = model.manager(id);
        ImDrawList draw = ImGui.getWindowDrawList();
        if (info.isEmpty()) {
            w.dashedRect(draw, x + 0.5f, y + 0.5f, x + width - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER);
        } else {
            draw.addRectFilled(x, y, x + width, y + h, ImGuiTheme.COL_SURFACE, m.radiusLarge());
            draw.addRect(x + 0.5f, y + 0.5f, x + width - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER, m.radiusLarge());
        }
        boolean isOn = info.map(i -> i.state() == ManagerInfo.State.MANAGING).orElse(false);
        w.ui().iconTile(draw, x + m.u(4), y + m.u(3), icon, Icons.ROBOT,
                isOn ? ImGuiTheme.COL_INFO : ImGuiTheme.COL_FG2,
                isOn ? ImGuiTheme.COL_INFO_SOFT : ImGuiTheme.COL_ELEVATED);
        float tx = x + m.u(4) + icon + m.u(3);
        float actionsW = info.map(this::actionsWidth).orElseGet(this::assignWidth) + m.u(3);
        text(draw, info, detail.rows().size(), tx, y + m.u(3), x + width - actionsW - tx, icon);
        float right = x + width - m.u(3);
        if (info.isEmpty()) {
            assign(id, right, y, h);
        } else {
            actions(id, info.get(), right, y, h);
        }
        return h;
    }

    private void text(ImDrawList draw, Optional<ManagerInfo> info, int members, float x, float y, float width,
                      float h) {
        Controls ui = w.ui();
        float half = h * 0.5f;
        ImFont key = ui.fonts().captionMedium();
        ui.textCentredY(draw, key, x, y, half, ImGuiTheme.COL_FG3, KEY);
        float nx = x + ui.width(key, KEY) + w.m().u(2);
        String name = info.map(ManagerInfo::script).orElse("None");
        ImFont nameFont = info.isPresent() ? ui.fonts().smallMedium() : ui.fonts().small();
        ui.textCentredY(draw, nameFont, nx, y, half, info.isPresent() ? ImGuiTheme.COL_FG : ImGuiTheme.COL_FG2,
                name);
        float after = nx + ui.width(nameFont, name) + w.m().u(1.5f);
        if (info.isPresent()) {
            String version = info.get().version().isBlank() ? "" : "v" + info.get().version();
            ui.textCentredY(draw, ui.fonts().monoCaption(), after, y, half, ImGuiTheme.COL_FG2, version);
            float cx = after + (version.isEmpty() ? 0f : ui.width(ui.fonts().monoCaption(), version) + w.m().u(2));
            chip(draw, info.get().state(), cx, y, half);
        }
        String line = info.map(i -> i.line(members)).orElse(EMPTY_LINE);
        ui.textCentredY(draw, ui.fonts().caption(), x, y + half, half, ImGuiTheme.COL_FG2,
                ui.ellipsize(ui.fonts().caption(), line, width));
    }

    private void chip(ImDrawList draw, ManagerInfo.State state, float x, float y, float h) {
        float cy = y + (h - w.m().chipHeight()) * 0.5f;
        int fg = switch (state) {
            case MANAGING -> ImGuiTheme.COL_INFO;
            case PAUSED, NOT_LOADED -> ImGuiTheme.COL_WARN;
            case STOPPED -> ImGuiTheme.COL_FG2;
        };
        int bg = switch (state) {
            case MANAGING -> ImGuiTheme.COL_INFO_SOFT;
            case PAUSED, NOT_LOADED -> ImGuiTheme.COL_WARN_SOFT;
            case STOPPED -> ImGuiTheme.COL_ELEVATED;
        };
        w.ui().chip(draw, x, cy, state.label(), fg, bg, 1f);
    }

    // ── Buttons ────────────────────────────────────────────────────────────

    private float assignWidth() {
        return w.ui().buttonWidth(Icons.ROBOT, "Assign manager", Tone.GHOST);
    }

    private void assign(GroupId id, float right, float y, float h) {
        float bh = w.m().controlSmallHeight();
        ImGui.setCursorScreenPos(right - assignWidth(), y + (h - bh) * 0.5f);
        if (w.ui().button("##group-assign-manager", Icons.ROBOT, "Assign manager", Tone.GHOST, true, bh)) {
            openAssign.accept(id);
        }
    }

    private float actionsWidth(ManagerInfo info) {
        float s = w.m().controlSmallHeight();
        float icons = info.canStart() ? s * 2f + w.m().u(0.5f) : s;
        return icons + w.m().u(1) + w.ui().buttonWidth(null, "Change", Tone.GHOST);
    }

    /** Settings, Start while it is not managing, and Change; right-aligned to {@code right}. */
    private void actions(GroupId id, ManagerInfo info, float right, float y, float h) {
        ImGuiTheme.Metrics m = w.m();
        float s = m.controlSmallHeight();
        float by = y + (h - s) * 0.5f;
        float bx = right - actionsWidth(info);
        ImGui.setCursorScreenPos(bx, by);
        ImGui.beginDisabled(!info.hasSettings());
        if (w.iconButton("##manager-settings", Icons.SLIDERS, ImGuiTheme.COL_FG2, ImGuiTheme.COL_ELEVATED,
                info.hasSettings() ? "Settings for " + info.script() + " on this group"
                        : info.script() + " has no settings")) {
            model.openManagerSettings(id);
        }
        ImGui.endDisabled();
        bx += s + m.u(0.5f);
        if (info.canStart()) {
            ImGui.setCursorScreenPos(bx, by);
            if (w.iconButton("##manager-start", Icons.PLAY, ImGuiTheme.COL_ACCENT, ImGuiTheme.COL_ACCENT_SOFT,
                    "Start " + info.script() + " managing this group")) {
                model.startManager(id);
            }
            bx += s + m.u(0.5f);
        }
        ImGui.setCursorScreenPos(bx + m.u(0.5f), by);
        if (w.ui().button("##manager-change", null, "Change", Tone.GHOST, true, s)) {
            openAssign.accept(id);
        }
    }
}
