package com.botwithus.bot.cli.gui.pages.management;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.List;

/**
 * The Overview tab: the description, what the script applies to (with the add
 * row), its last crash, health, source and latest activity. Draws from the
 * cursor down and leaves the cursor below what it drew.
 */
final class OverviewTab {

    private static final float PARAGRAPH_LINE = 1.45f;
    private static final float KV_LINE = 1.75f;
    private static final int LATEST = 2;

    /** One line of a key/value section; a bad value is drawn in red. */
    private record KeyValue(String key, String value, boolean isBad) {
        KeyValue(String key, String value) {
            this(key, value, false);
        }
    }

    private final ManagementWidgets w;
    private final TargetsSection targets;
    private final ActivityTab activity;

    OverviewTab(ManagementWidgets widgets) {
        this.w = widgets;
        this.targets = new TargetsSection(widgets);
        this.activity = new ActivityTab(widgets);
    }

    void render(ManagementView view, ManagementRow row, ManagementState state, float width) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImDrawList draw = ImGui.getWindowDrawList();
        Controls ui = w.ui();
        float gap = w.m().u(5);
        float cy = y;
        if (!row.about().description().isBlank()) {
            ImFont font = ui.fonts().small();
            cy += w.paragraph(draw, font, x, cy, width, font.getFontSize() * PARAGRAPH_LINE, ImGuiTheme.COL_FG2,
                    row.about().description()) + gap;
        }
        cy += targets.render(view, row, state, x, cy, width) + gap;
        if (row.health().lastCrash().isPresent()) {
            cy += lastCrash(draw, row.health().lastCrash().get(), x, cy, width) + gap;
        }
        cy += keyValues(draw, "Health", health(row.health()), x, cy, width) + gap;
        cy += keyValues(draw, "Source", source(row.about()), x, cy, width) + gap;
        cy += latest(draw, row, x, cy, width);
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, cy - y);
    }

    private float lastCrash(ImDrawList draw, String crash, float x, float y, float width) {
        Controls ui = w.ui();
        w.heading(draw, x, y, "Last crash");
        float top = y + headingHeight();
        ImFont font = ui.fonts().monoCaption();
        return headingHeight() + w.paragraph(draw, font, x, top, width, font.getFontSize() * PARAGRAPH_LINE,
                ImGuiTheme.COL_DANGER, crash);
    }

    private static List<KeyValue> health(RunHealth health) {
        return List.of(
                new KeyValue("State", health.stateLine()),
                new KeyValue("Loops", ManagementText.thousands(health.loops())),
                new KeyValue("Avg loop", health.avgLoopMs().isPresent()
                        ? ManagementText.loopMs(health.avgLoopMs()) + " ms" : ManagementText.NONE),
                new KeyValue("Crashes", Long.toString(health.crashes()), health.crashes() > 0));
    }

    private static List<KeyValue> source(ScriptAbout about) {
        return List.of(
                new KeyValue("Class", about.simpleClassName()),
                new KeyValue("Author", about.author().isBlank() ? ManagementText.NONE : about.author()),
                new KeyValue("Settings", about.settingsLine()));
    }

    /** A heading over key/value lines, keys on the left and mono values on the right; returns its height. */
    private float keyValues(ImDrawList draw, String title, List<KeyValue> lines, float x, float y, float width) {
        Controls ui = w.ui();
        w.heading(draw, x, y, title);
        float lineH = ui.fonts().caption().getFontSize() * KV_LINE;
        float cy = y + headingHeight();
        for (KeyValue kv : lines) {
            ui.textCentredY(draw, ui.fonts().caption(), x, cy, lineH, ImGuiTheme.COL_FG2, kv.key());
            float keyW = ui.width(ui.fonts().caption(), kv.key()) + w.m().u(4);
            String value = ui.ellipsize(ui.fonts().monoCaption(), kv.value(), width - keyW);
            ui.textCentredY(draw, ui.fonts().monoCaption(), x + width - ui.width(ui.fonts().monoCaption(), value),
                    cy, lineH, kv.isBad() ? ImGuiTheme.COL_DANGER : ImGuiTheme.COL_FG, value);
            cy += lineH;
        }
        return cy - y;
    }

    /** The newest two orchestrator calls. */
    private float latest(ImDrawList draw, ManagementRow row, float x, float y, float width) {
        w.heading(draw, x, y, "Latest");
        List<ActivityRow> newest = row.activity().subList(0, Math.min(LATEST, row.activity().size()));
        return headingHeight() + activity.log(draw, newest, x, y + headingHeight(), width);
    }

    private float headingHeight() {
        return w.ui().fonts().captionMedium().getFontSize() + w.m().u(2);
    }
}
