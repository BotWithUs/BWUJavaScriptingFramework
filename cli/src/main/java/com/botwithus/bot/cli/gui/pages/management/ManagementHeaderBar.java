package com.botwithus.bot.cli.gui.pages.management;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

/**
 * The top of the page: the title and its meta line, then Open folder, Reload
 * and Stop all, which asks inline before it stops anything. Under them, the
 * intro line on what management scripts are, with a rule under it.
 */
final class ManagementHeaderBar {

    private static final String TITLE = "Management";
    private static final String INTRO_ONCE = "Management scripts run once for the host, not per client."
            + " They start, stop and schedule client scripts for you.";
    private static final String INTRO_TARGETS = "Apply one to the whole host, a group, or a single"
            + " client's script.";
    private static final float SPINNER_EM = 0.367f;
    private static final float LEDE_LINE = 1.45f;

    private final ManagementWidgets w;

    ManagementHeaderBar(ManagementWidgets widgets) {
        this.w = widgets;
    }

    /** The height of the title row and the intro under it, at {@code width}. */
    float height(float width) {
        ImGuiTheme.Metrics m = w.m();
        return m.u(4) + m.controlHeight() + m.u(2) + ledeHeight(width) + m.u(3);
    }

    /** Draws the header from (x, y), {@code width} wide, with its rule from {@code ruleX0} to {@code ruleX1}. */
    void render(ManagementView view, ManagementState state, float x, float y, float width, float ruleX0,
                float ruleX1) {
        ImGuiTheme.Metrics m = w.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        Controls ui = w.ui();
        float top = y + m.u(4);
        float h = m.controlHeight();
        ui.textCentredY(draw, ui.fonts().titleMedium(), x, top, h, ImGuiTheme.COL_FG, TITLE);
        float metaX = x + ui.width(ui.fonts().titleMedium(), TITLE) + m.u(3);
        drawMeta(draw, view, metaX, top, h);
        if (state.isStopAllArmed() && view.runningCount() > 0) {
            confirm(view, state, x + width, top);
        } else {
            state.disarmStopAll();
            buttons(view, state, x + width, top);
        }
        float ledeY = top + h + m.u(2);
        lede(draw, x, ledeY, width);
        float ruleY = y + height(width) - m.hairline();
        draw.addLine(ruleX0, ruleY, ruleX1, ruleY, ImGuiTheme.COL_BORDER, m.hairline());
    }

    private void drawMeta(ImDrawList draw, ManagementView view, float x, float y, float h) {
        Controls ui = w.ui();
        if (!view.isReloading()) {
            ui.textCentredY(draw, ui.fonts().monoCaption(), x, y, h, ImGuiTheme.COL_FG2, view.meta());
            return;
        }
        float r = w.fs() * SPINNER_EM;
        w.spinner(draw, x + r, y + h * 0.5f, r);
        ui.textCentredY(draw, ui.fonts().monoCaption(), x + r * 2f + w.m().u(1.5f), y, h, ImGuiTheme.COL_FG2,
                "Loading " + view.folderLabel() + "…");
    }

    /** Open folder, Reload and Stop all, right-aligned to {@code right}. */
    private void buttons(ManagementView view, ManagementState state, float right, float y) {
        Controls ui = w.ui();
        ImGuiTheme.Metrics m = w.m();
        float stopW = ui.buttonWidth(Icons.STOP, "Stop all", Tone.STOP);
        float reloadW = ui.buttonWidth(Icons.ROTATE, "Reload", Tone.GHOST);
        float folderW = w.linkWidth(Icons.FOLDER_OPEN, "Open folder");
        ImGui.setCursorScreenPos(right - stopW - reloadW - folderW - m.u(2) * 2f, y);
        if (w.link("##mgmt-open-folder", Icons.FOLDER_OPEN, "Open folder", m.controlHeight())) {
            state.model().openFolder();
        }
        ImGui.sameLine(0f, m.u(2));
        if (ui.button("##mgmt-reload", Icons.ROTATE, "Reload", Tone.GHOST, !view.isReloading())) {
            state.model().reload();
        }
        ImGui.sameLine(0f, m.u(2));
        if (ui.button("##mgmt-stop-all", Icons.STOP, "Stop all", Tone.STOP, view.runningCount() > 0)) {
            state.armStopAll();
        }
    }

    /** "Stop 2 management scripts? Client scripts keep running." with Stop all and Cancel. */
    private void confirm(ManagementView view, ManagementState state, float right, float y) {
        Controls ui = w.ui();
        ImGuiTheme.Metrics m = w.m();
        float h = m.controlHeight();
        String question = "Stop " + ManagementText.count(view.runningCount(), "management script")
                + "? Client scripts keep running.";
        float stopW = ui.buttonWidth(null, "Stop all", Tone.STOP);
        float cancelW = w.linkWidth(null, "Cancel");
        float qW = ui.width(ui.fonts().small(), question);
        float x = right - cancelW - stopW - qW - m.u(2) * 2f;
        ui.textCentredY(ImGui.getWindowDrawList(), ui.fonts().small(), x, y, h, ImGuiTheme.COL_FG, question);
        ImGui.setCursorScreenPos(x + qW + m.u(2), y);
        if (ui.button("##mgmt-stop-all-yes", null, "Stop all", Tone.STOP, true)) {
            state.confirmStopAll();
        }
        ImGui.sameLine(0f, m.u(2));
        if (w.link("##mgmt-stop-all-no", null, "Cancel", h)) {
            state.disarmStopAll();
        }
    }

    // ── Intro ──────────────────────────────────────────────────────────────

    private float ledeHeight(float width) {
        ImFont font = w.ui().fonts().small();
        float lineH = font.getFontSize() * LEDE_LINE;
        float textW = width - ledeIconWidth();
        return (w.ui().wrap(font, INTRO_ONCE, textW).size() + w.ui().wrap(font, INTRO_TARGETS, textW).size())
                * lineH;
    }

    private float ledeIconWidth() {
        return w.ui().width(w.ui().fonts().caption(), Icons.CROSSHAIRS) + w.m().u(2);
    }

    /** The two intro sentences, each after its glyph, one under the other. */
    private void lede(ImDrawList draw, float x, float y, float width) {
        Controls ui = w.ui();
        ImFont font = ui.fonts().small();
        float lineH = font.getFontSize() * LEDE_LINE;
        float tx = x + ledeIconWidth();
        float cy = y;
        ui.textCentredY(draw, ui.fonts().caption(), x, cy, lineH, ImGuiTheme.COL_FG3, Icons.ROBOT);
        cy += w.paragraph(draw, font, tx, cy, width - ledeIconWidth(), lineH, ImGuiTheme.COL_FG2, INTRO_ONCE);
        ui.textCentredY(draw, ui.fonts().caption(), x, cy, lineH, ImGuiTheme.COL_FG3, Icons.CROSSHAIRS);
        w.paragraph(draw, font, tx, cy, width - ledeIconWidth(), lineH, ImGuiTheme.COL_FG2, INTRO_TARGETS);
    }
}
