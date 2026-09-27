package com.botwithus.bot.cli.gui.pages.management;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.management.Target;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.Optional;

/**
 * The Settings and Script UI tabs. Both open the one shared inspector, which
 * docks beside this page, rather than drawing a second copy of its form: the
 * Settings tab lists what there is to edit (the defaults every target uses,
 * and each target's own values) with a button for each, and the Script UI tab
 * says where the script draws its UI.
 */
final class SettingsTab {

    private static final float LINE = 1.45f;
    private static final float ROW_LINE = 1.35f;
    private static final String DEFAULTS = "Defaults · every target";
    private static final String NOTE = "Settings open in the inspector beside this page. They apply live"
            + " through onConfigUpdate, with no restart. A target's own value wins over its group's, and a"
            + " group's over the defaults.";
    private static final String WHOLE_HOST_NOTE = "It manages the whole host, so every client uses the"
            + " defaults.";

    private final ManagementWidgets w;

    SettingsTab(ManagementWidgets widgets) {
        this.w = widgets;
    }

    void render(ManagementRow row, ManagementState state, float width) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImDrawList draw = ImGui.getWindowDrawList();
        ImGuiTheme.Metrics m = w.m();
        ImFont font = w.ui().fonts().caption();
        float lineH = font.getFontSize() * LINE;
        float cy = y;
        if (row.about().settingsCount() == 0) {
            cy += w.paragraph(draw, w.ui().fonts().small(), x, cy, width, lineH, ImGuiTheme.COL_FG2,
                    row.name() + " declares no settings.");
            finish(x, y, width, cy);
            return;
        }
        w.heading(draw, x, cy, "Settings for");
        cy += w.ui().fonts().captionMedium().getFontSize() + m.u(2);
        cy += choices(draw, row, state, x, cy, width) + m.u(3);
        String note = row.isWholeHost() ? WHOLE_HOST_NOTE + " " + NOTE : NOTE;
        cy += w.paragraph(draw, font, x, cy, width, lineH, ImGuiTheme.COL_FG2, note);
        finish(x, y, width, cy);
    }

    /** The defaults, then each target that can have values of its own, each with an Edit button. */
    private float choices(ImDrawList draw, ManagementRow row, ManagementState state, float x, float y,
                          float width) {
        float rowH = rowHeight();
        int count = 1;
        String fields = ManagementText.count(row.about().settingsCount(), "field");
        choice(draw, row, state, Optional.empty(), DEFAULTS, fields, false,
                x, y, width);
        for (TargetRow target : row.targets()) {
            if (target.isWholeHost()) {
                continue;
            }
            String own = target.ownSettings() > 0
                    ? ManagementText.count(target.ownSettings(), "own value") : "follows what it inherits";
            float ry = y + rowH * count;
            draw.addLine(x + 1f, ry, x + width - 1f, ry, ImGuiTheme.COL_BORDER, w.m().hairline());
            choice(draw, row, state, Optional.of(target.target()), target.label(), own, target.ownSettings() > 0,
                    x, ry, width);
            count++;
        }
        float h = rowH * count;
        draw.addRect(x + 0.5f, y + 0.5f, x + width - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER, w.m().radius());
        return h;
    }

    private float rowHeight() {
        Controls ui = w.ui();
        return w.m().u(1.5f) * 2f + (ui.fonts().small().getFontSize() + ui.fonts().caption().getFontSize())
                * ROW_LINE;
    }

    private void choice(ImDrawList draw, ManagementRow row, ManagementState state, Optional<Target> target,
                        String label, String sub, boolean hasOwn, float x, float y, float width) {
        Controls ui = w.ui();
        ImGuiTheme.Metrics m = w.m();
        float h = rowHeight();
        float editW = ui.buttonWidth(Icons.SLIDERS, "Edit", Tone.GHOST);
        float tx = x + m.u(3);
        float textW = width - editW - m.u(3) * 2f - m.u(2);
        float top = y + m.u(1.5f);
        float lineH = ui.fonts().small().getFontSize() * ROW_LINE;
        ui.textCentredY(draw, ui.fonts().small(), tx, top, lineH, ImGuiTheme.COL_FG,
                ui.ellipsize(ui.fonts().small(), label, textW));
        int subCol = hasOwn ? ImGuiTheme.COL_INFO : ImGuiTheme.COL_FG2;
        ui.textCentredY(draw, ui.fonts().caption(), tx, top + lineH, ui.fonts().caption().getFontSize() * ROW_LINE,
                subCol, ui.ellipsize(ui.fonts().caption(), sub, textW));
        ImGui.setCursorScreenPos(x + width - m.u(3) - editW, y + (h - m.controlSmallHeight()) * 0.5f);
        String id = "##sfor:" + row.name() + ":" + target.map(Target::key).orElse("defaults");
        if (ui.button(id, Icons.SLIDERS, "Edit", Tone.GHOST, true, m.controlSmallHeight())) {
            state.model().openSettings(row.name(), target);
        }
    }

    /** The Script UI tab: what draws there, and a button to show it. */
    void renderScriptUi(ManagementRow row, ManagementState state, float width) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImDrawList draw = ImGui.getWindowDrawList();
        ImGuiTheme.Metrics m = w.m();
        ImFont font = w.ui().fonts().caption();
        w.heading(draw, x, y, "Drawn by " + row.name());
        float cy = y + w.ui().fonts().captionMedium().getFontSize() + m.u(2);
        cy += w.paragraph(draw, font, x, cy, width, font.getFontSize() * LINE, ImGuiTheme.COL_FG2,
                "The script draws its own UI. It shows in the inspector beside this page, framed by the host"
                        + " and not restyled.") + m.u(3);
        ImGui.setCursorScreenPos(x, cy);
        if (w.ui().button("##mgmt-open-ui", Icons.WINDOW, "Show its UI", Tone.GHOST, true)) {
            state.model().openScriptUi(row.name());
        }
        finish(x, y, width, cy + m.controlHeight());
    }

    private static void finish(float x, float y, float width, float bottom) {
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, bottom - y);
    }
}
