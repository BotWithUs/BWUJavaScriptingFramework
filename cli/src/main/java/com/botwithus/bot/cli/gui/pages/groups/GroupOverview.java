package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.groups.ManagerSlot;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.Optional;

/**
 * The group's summary row (running with a bar, main script, how many need a
 * look, average loop) and its manager slot under it.
 *
 * <p>Assigning a manager arrives with management targets. Until then an empty
 * slot is drawn disabled and says so, and a manager already in the group file
 * is shown read-only.</p>
 */
final class GroupOverview {

    private static final int COLUMNS = 4;
    private static final float LINE = 1.4f;
    private static final float BAR_EM = 0.4f;
    private static final float MANAGER_ICON_EM = 2.267f;
    private static final String COMING = "Coming with Management: management scripts cannot be scoped"
            + " to a group yet.";

    private final GroupWidgets w;

    GroupOverview(GroupWidgets widgets) {
        this.w = widgets;
    }

    /** Draws the summary row and manager slot at (x, y), {@code width} wide; returns their height. */
    float render(GroupDetail detail, float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        float left = x + m.u(5);
        float inner = width - m.u(5) * 2f;
        float summaryH = summary(detail.summary(), left, y, inner);
        float managerH = manager(detail.group().manager(), left, y + summaryH + m.u(3), inner);
        return summaryH + m.u(3) + managerH;
    }

    private float summary(GroupSummary s, float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        Controls ui = w.ui();
        float capH = ui.fonts().caption().getFontSize() * LINE;
        float valH = ui.fonts().bodyMedium().getFontSize() * LINE;
        float barH = w.fs() * BAR_EM;
        float h = m.u(3) * 2f + capH + valH + m.u(1.5f) + barH;
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + h, ImGuiTheme.COL_SURFACE, m.radiusLarge());
        draw.addRect(x + 0.5f, y + 0.5f, x + width - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER, m.radiusLarge());
        float colW = width / COLUMNS;
        for (int i = 1; i < COLUMNS; i++) {
            draw.addLine(x + colW * i, y + 1f, x + colW * i, y + h - 1f, ImGuiTheme.COL_BORDER);
        }
        float cy = y + m.u(3);
        float pad = m.u(4);
        figure("Running", Integer.toString(s.running()), "of " + s.size(), ImGuiTheme.COL_FG, x + pad, cy,
                colW - pad * 2f);
        runningBar(s, x + pad, cy + capH + valH + m.u(1.5f), colW - pad * 2f, barH);
        String others = s.otherScripts() > 0 ? "+" + s.otherScripts() + " other" : "";
        figure("Script", s.mainScript().orElse("none"), others,
                s.mainScript().isPresent() ? ImGuiTheme.COL_FG : ImGuiTheme.COL_FG2, x + colW + pad, cy,
                colW - pad * 2f);
        figure("Needs a look", Integer.toString(s.needsLook()), s.needsLook() > 0 ? "stalled or crashed" : "all fine",
                s.needsLook() > 0 ? ImGuiTheme.COL_WARN : ImGuiTheme.COL_FG, x + colW * 2f + pad, cy,
                colW - pad * 2f);
        boolean hasLoop = s.avgLoopMs().isPresent();
        figure("Avg loop", GroupText.loopMs(s.avgLoopMs()), hasLoop ? "ms" : "", ImGuiTheme.COL_FG,
                x + colW * 3f + pad, cy, colW - pad * 2f);
        return h;
    }

    /** A caption over a value with a small grey suffix, clipped to {@code width}. */
    private void figure(String caption, String value, String suffix, int col, float x, float y, float width) {
        Controls ui = w.ui();
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont cap = ui.fonts().caption();
        ImFont val = ui.fonts().bodyMedium();
        ui.text(draw, cap, x, y, ImGuiTheme.COL_FG2, caption);
        float vy = y + cap.getFontSize() * LINE;
        float valH = val.getFontSize() * LINE;
        String shown = ui.ellipsize(val, value, width);
        ui.textCentredY(draw, val, x, vy, valH, col, shown);
        float sx = x + ui.width(val, shown) + w.m().u(1);
        if (!suffix.isEmpty() && sx < x + width) {
            ui.textCentredY(draw, cap, sx, vy + 1f, valH, ImGuiTheme.COL_FG2,
                    ui.ellipsize(cap, suffix, x + width - sx));
        }
    }

    private static void runningBar(GroupSummary s, float x, float y, float width, float h) {
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + h, ImGuiTheme.COL_ELEVATED, h * 0.5f);
        if (s.size() == 0) {
            return;
        }
        float runW = width * s.running() / s.size();
        float lookW = width * s.needsLook() / s.size();
        if (runW > 0f) {
            draw.addRectFilled(x, y, x + runW, y + h, ImGuiTheme.COL_ACCENT, h * 0.5f);
        }
        if (lookW > 0f) {
            draw.addRectFilled(x + runW, y, x + runW + lookW, y + h, ImGuiTheme.COL_WARN, h * 0.5f);
        }
    }

    private float manager(Optional<ManagerSlot> slot, float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        Controls ui = w.ui();
        float icon = w.fs() * MANAGER_ICON_EM;
        float h = m.u(3) * 2f + icon;
        ImDrawList draw = ImGui.getWindowDrawList();
        if (slot.isEmpty()) {
            w.dashedRect(draw, x + 0.5f, y + 0.5f, x + width - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER);
        } else {
            draw.addRectFilled(x, y, x + width, y + h, ImGuiTheme.COL_SURFACE, m.radiusLarge());
            draw.addRect(x + 0.5f, y + 0.5f, x + width - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER, m.radiusLarge());
        }
        boolean isOn = slot.filter(ManagerSlot::shouldRun).isPresent();
        ui.iconTile(draw, x + m.u(4), y + m.u(3), icon, Icons.ROBOT,
                isOn ? ImGuiTheme.COL_INFO : ImGuiTheme.COL_FG2,
                isOn ? ImGuiTheme.COL_INFO_SOFT : ImGuiTheme.COL_ELEVATED);
        float tx = x + m.u(4) + icon + m.u(3);
        float assignW = slot.isEmpty() ? ui.buttonWidth(Icons.ROBOT, "Assign manager", Tone.GHOST) + m.u(3) : 0f;
        float textW = x + width - m.u(3) - assignW - tx;
        managerText(slot, tx, y + m.u(3), textW, icon);
        if (slot.isEmpty()) {
            assignButton(x + width - assignW, y, h);
        }
        return h;
    }

    private void managerText(Optional<ManagerSlot> slot, float x, float y, float width, float h) {
        Controls ui = w.ui();
        ImDrawList draw = ImGui.getWindowDrawList();
        float half = h * 0.5f;
        ImFont key = ui.fonts().captionMedium();
        String label = "MANAGER";
        ui.textCentredY(draw, key, x, y, half, ImGuiTheme.COL_FG3, label);
        float nx = x + ui.width(key, label) + w.m().u(2);
        String name = slot.map(ManagerSlot::script).orElse("None");
        ImFont nameFont = slot.isPresent() ? ui.fonts().smallMedium() : ui.fonts().small();
        ui.textCentredY(draw, nameFont, nx, y, half, slot.isPresent() ? ImGuiTheme.COL_FG : ImGuiTheme.COL_FG2,
                name);
        slot.ifPresent(s -> managerChip(draw, s, nx + ui.width(nameFont, name) + w.m().u(2), y, half));
        String line = slot.map(s -> s.shouldRun()
                        ? "Assigned to this group. Running it here comes with Management."
                        : "Paused by Stop all, so it does not start what was stopped.")
                .orElse("A management script can start, stop and schedule scripts on this group's clients.");
        ui.textCentredY(draw, ui.fonts().caption(), x, y + half, half, ImGuiTheme.COL_FG2,
                ui.ellipsize(ui.fonts().caption(), line, width));
    }

    private void managerChip(ImDrawList draw, ManagerSlot slot, float x, float y, float h) {
        Controls ui = w.ui();
        float cy = y + (h - w.m().chipHeight()) * 0.5f;
        if (slot.shouldRun()) {
            ui.chip(draw, x, cy, "Should run", ImGuiTheme.COL_INFO, ImGuiTheme.COL_INFO_SOFT, 1f);
        } else {
            ui.chip(draw, x, cy, "Paused", ImGuiTheme.COL_WARN, ImGuiTheme.COL_WARN_SOFT, 1f);
        }
    }

    /** Disabled until management targets land; the tooltip says so. */
    private void assignButton(float x, float y, float h) {
        Controls ui = w.ui();
        float bh = w.m().controlSmallHeight();
        float bw = ui.buttonWidth(Icons.ROBOT, "Assign manager", Tone.GHOST);
        ImGui.setCursorScreenPos(x, y + (h - bh) * 0.5f);
        ui.button("##group-assign-manager", Icons.ROBOT, "Assign manager", Tone.GHOST, false, bh);
        if (ImGui.isMouseHoveringRect(x, y + (h - bh) * 0.5f, x + bw, y + (h + bh) * 0.5f)) {
            w.tooltip(COMING);
        }
    }
}
