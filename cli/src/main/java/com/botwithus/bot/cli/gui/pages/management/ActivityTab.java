package com.botwithus.bot.cli.gui.pages.management;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.List;

/**
 * The Activity tab: every orchestrator call the script made that starts,
 * stops, schedules or changes something, newest first. The Overview's
 * "Latest" draws its first two lines the same way.
 */
final class ActivityTab {

    private static final float LINE = 1.45f;
    private static final float TIME_EM = 3.2f;
    /** How the audit log words a call its targets refused, and one that failed. */
    private static final String REFUSED = "refused";
    private static final String FAILED = "failed";
    private static final String NOTHING = "No orchestrator calls yet.";
    private static final String NOTE = "Every ClientOrchestrator call the script makes that starts, stops,"
            + " schedules or changes something is logged here, newest first. Lists and statuses are not.";

    private final ManagementWidgets w;

    ActivityTab(ManagementWidgets widgets) {
        this.w = widgets;
    }

    void render(ManagementRow row, float width) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImDrawList draw = ImGui.getWindowDrawList();
        ImGuiTheme.Metrics m = w.m();
        w.heading(draw, x, y, "What it did");
        float cy = y + w.ui().fonts().captionMedium().getFontSize() + m.u(2);
        cy += log(draw, row.activity(), x, cy, width) + m.u(3);
        ImFont font = w.ui().fonts().caption();
        cy += w.paragraph(draw, font, x, cy, width, font.getFontSize() * LINE, ImGuiTheme.COL_FG2, NOTE);
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, cy - y);
    }

    /** The calls as log lines, a rule between each: the time, then what it called and how it went. */
    float log(ImDrawList draw, List<ActivityRow> rows, float x, float y, float width) {
        Controls ui = w.ui();
        ImGuiTheme.Metrics m = w.m();
        ImFont font = ui.fonts().caption();
        float lineH = font.getFontSize() * LINE;
        if (rows.isEmpty()) {
            ui.textCentredY(draw, font, x, y, lineH, ImGuiTheme.COL_FG3, NOTHING);
            return lineH;
        }
        float timeW = w.fs() * TIME_EM;
        float textW = width - timeW - m.u(2);
        float cy = y;
        for (int i = 0; i < rows.size(); i++) {
            ActivityRow row = rows.get(i);
            if (i > 0) {
                draw.addLine(x, cy, x + width, cy, ImGuiTheme.COL_BORDER, m.hairline());
            }
            float top = cy + m.u(1.5f);
            ui.textCentredY(draw, ui.fonts().monoCaption(), x, top, lineH, ImGuiTheme.COL_FG3, row.time());
            float h = w.paragraph(draw, font, x + timeW + m.u(2), top, textW, lineH, ImGuiTheme.COL_FG, row.what());
            h += w.paragraph(draw, font, x + timeW + m.u(2), top + h, textW, lineH, resultColor(row),
                    row.result());
            cy = top + h + m.u(1.5f);
        }
        return cy - y;
    }

    /** A refused or failed call reads red; everything else is secondary text. */
    private static int resultColor(ActivityRow row) {
        boolean isBad = row.result().startsWith(REFUSED) || row.result().startsWith(FAILED);
        return isBad ? ImGuiTheme.COL_DANGER : ImGuiTheme.COL_FG2;
    }
}
