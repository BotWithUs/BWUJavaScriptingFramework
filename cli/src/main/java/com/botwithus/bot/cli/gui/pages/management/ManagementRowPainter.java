package com.botwithus.bot.cli.gui.pages.management;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.management.Target;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One row of the scripts list: the robot tile, name and version over the
 * description, the Applies to chips, the state, the average loop, and
 * Settings with Stop and Restart, or Start.
 */
final class ManagementRowPainter {

    /** The prototype's row height, 56 px at a 15 px body font. */
    private static final float ROW_EM = 3.733f;
    private static final float HEAD_EM = 2.133f;
    private static final float APPLIES_MIN_EM = 7.333f;
    private static final float NAME_MIN_EM = 8f;
    private static final float STATE_EM = 7.333f;
    private static final float LOOP_EM = 3.2f;
    private static final float NAME_SHARE = 1.4f;
    private static final float APPLIES_SHARE = 1f;
    private static final int MAX_CHIP_LINES = 2;
    private static final int ACTIONS = 3;

    /**
     * Where each column starts and how wide it is, for one list width. On a
     * narrow list the Loop column is left out, so the name keeps its room; the
     * detail pane's Health shows the loop.
     */
    record Columns(float icon, float name, float nameW, float applies, float appliesW, float state,
                   boolean hasLoop, float loop, float loopW, float actions) {}

    /** One chip laid out: where it goes and what it shows. */
    private record Chip(float x, float y, float w, String icon, String label, Target target, boolean isMore) {}

    private final ManagementWidgets w;

    ManagementRowPainter(ManagementWidgets widgets) {
        this.w = widgets;
    }

    float rowHeight() {
        return w.fs() * ROW_EM;
    }

    float headHeight() {
        return w.fs() * HEAD_EM;
    }

    Columns columns(float x, float width) {
        ImGuiTheme.Metrics m = w.m();
        float gap = m.u(3);
        float left = x + m.u(4);
        float right = x + width - m.u(3);
        float stateW = w.fs() * STATE_EM;
        float fixed = m.iconTile() + stateW + actionsWidth() + gap * 4f;
        float loopW = w.fs() * LOOP_EM + gap;
        float wideFlex = right - left - fixed - loopW;
        boolean hasLoop = wideFlex >= w.fs() * (NAME_MIN_EM + APPLIES_MIN_EM);
        float flex = hasLoop ? wideFlex : right - left - fixed;
        float nameW = Math.max(w.fs() * NAME_MIN_EM, flex * NAME_SHARE / (NAME_SHARE + APPLIES_SHARE));
        float appliesW = Math.max(0f, flex - nameW);
        float name = left + m.iconTile() + gap;
        float applies = name + nameW + gap;
        float state = applies + appliesW + gap;
        float loop = state + stateW + gap;
        return new Columns(left, name, nameW, applies, appliesW, state, hasLoop, loop, loopW - gap,
                right - actionsWidth());
    }

    private float actionsWidth() {
        return w.m().controlSmallHeight() * ACTIONS + w.m().u(0.5f) * (ACTIONS - 1);
    }

    /** The column captions over the rows. */
    void head(ImDrawList draw, Columns c, float y) {
        Controls ui = w.ui();
        ImFont font = ui.fonts().captionMedium();
        float h = headHeight();
        ui.textCentredY(draw, font, c.name(), y, h, ImGuiTheme.COL_FG2, "Script");
        ui.textCentredY(draw, font, c.applies(), y, h, ImGuiTheme.COL_FG2, "Applies to");
        ui.textCentredY(draw, font, c.state(), y, h, ImGuiTheme.COL_FG2, "State");
        if (c.hasLoop()) {
            String loop = "Loop";
            ui.textCentredY(draw, font, c.loop() + c.loopW() - ui.width(font, loop), y, h, ImGuiTheme.COL_FG2,
                    loop);
        }
    }

    /** Paints a row's passive parts, its top at {@code y}; the chips and buttons answer clicks. */
    void paint(ManagementRow row, ManagementState state, Columns c, float y) {
        ImDrawList draw = ImGui.getWindowDrawList();
        ImGuiTheme.Metrics m = w.m();
        float h = rowHeight();
        float tile = m.iconTile();
        RunState run = row.health().state();
        w.ui().iconTile(draw, c.icon(), y + (h - tile) * 0.5f, tile, Icons.ROBOT, ManagementWidgets.tileFg(run),
                ManagementWidgets.tileBg(run));
        paintName(draw, row, c, y, h);
        paintAppliesTo(draw, row, state, c, y, h);
        w.state(draw, c.state(), y, h, run);
        if (c.hasLoop()) {
            paintLoop(draw, row, c, y, h);
        }
        actions(row, state, c, y, h);
    }

    private void paintName(ImDrawList draw, ManagementRow row, Columns c, float y, float h) {
        Controls ui = w.ui();
        ImFont nameFont = ui.fonts().smallMedium();
        ImFont mono = ui.fonts().monoCaption();
        float line1 = nameFont.getFontSize();
        float line2 = ui.fonts().caption().getFontSize();
        float top = y + (h - line1 - line2 - w.m().u(1)) * 0.5f;
        draw.pushClipRect(c.name(), y, c.name() + c.nameW(), y + h, true);
        ui.text(draw, nameFont, c.name(), top, ImGuiTheme.COL_FG, row.name());
        String version = row.about().versionLabel();
        if (!version.isEmpty()) {
            float vx = c.name() + ui.width(nameFont, row.name()) + w.m().u(1.25f);
            ui.text(draw, mono, vx, top + line1 - mono.getFontSize(), ImGuiTheme.COL_FG2, version);
        }
        ui.text(draw, ui.fonts().caption(), c.name(), top + line1 + w.m().u(1), ImGuiTheme.COL_FG2,
                ui.ellipsize(ui.fonts().caption(), row.about().description(), c.nameW()));
        draw.popClipRect();
    }

    // ── Applies to ─────────────────────────────────────────────────────────

    private void paintAppliesTo(ImDrawList draw, ManagementRow row, ManagementState state, Columns c, float y,
                                float h) {
        AppliesTo applies = row.appliesTo();
        float chipH = w.chipHeight();
        if (applies.isNotApplied()) {
            float cw = w.dashedChip(draw, c.applies(), y + (h - chipH) * 0.5f, AppliesTo.NOT_APPLIED);
            w.tooltipOver(c.applies(), y + (h - chipH) * 0.5f, cw, chipH,
                    "Not applied to anything, so it manages nothing");
            return;
        }
        List<Chip> chips = layout(applies, c);
        int lines = chips.isEmpty() ? 1 : (int) (chips.getLast().y() / (chipH + w.chipGap())) + 1;
        float blockH = lines * chipH + (lines - 1) * w.chipGap();
        float top = y + (h - blockH) * 0.5f;
        for (Chip chip : chips) {
            paintChip(draw, row, state, applies, chip, c.applies(), top);
        }
    }

    /** The chips flowed into the column, at most two lines; offsets are from the block's top-left. */
    private List<Chip> layout(AppliesTo applies, Columns c) {
        List<Chip> chips = new ArrayList<>();
        float lineStep = w.chipHeight() + w.chipGap();
        float moreW = applies.more().isEmpty() ? 0f : w.chipWidth(null, applies.more()) + w.chipGap();
        float cx = 0f;
        float cy = 0f;
        List<TargetRow> shown = applies.shown();
        for (int i = 0; i < shown.size(); i++) {
            String icon = ManagementWidgets.iconOf(shown.get(i).target());
            String label = shown.get(i).label();
            float cw = Math.min(w.chipWidth(icon, label), c.appliesW());
            if (cx > 0f && cx + cw > c.appliesW() && cy / lineStep < MAX_CHIP_LINES - 1) {
                cx = 0f;
                cy += lineStep;
            }
            boolean isLast = i == shown.size() - 1;
            float room = c.appliesW() - cx - (isLast ? moreW : 0f);
            float drawn = Math.max(0f, Math.min(cw, room));
            chips.add(new Chip(cx, cy, drawn, icon, label, shown.get(i).target(), false));
            cx += drawn + w.chipGap();
        }
        if (moreW > 0f) {
            chips.add(new Chip(cx, cy, moreW - w.chipGap(), null, applies.more(), null, true));
        }
        return chips;
    }

    private void paintChip(ImDrawList draw, ManagementRow row, ManagementState state, AppliesTo applies,
                           Chip chip, float left, float top) {
        float x = left + chip.x();
        float y = top + chip.y();
        float h = w.chipHeight();
        if (chip.isMore()) {
            w.chip(draw, x, y, null, chip.label(), ImGuiTheme.COL_FG2, chip.w());
            w.tooltipOver(x, y, chip.w(), h, applies.hiddenLabels());
            return;
        }
        float drawn = w.chip(draw, x, y, chip.icon(), chip.label(), ImGuiTheme.COL_FG, chip.w());
        boolean isGroup = switch (chip.target()) {
            case Target.Group _ -> true;
            case Target.Host _, Target.ClientScript _ -> false;
        };
        if (!isGroup) {
            w.tooltipOver(x, y, drawn, h, subOf(row, chip.target()));
            return;
        }
        ImGui.setCursorScreenPos(x, y);
        if (ImGui.invisibleButton("##chip:" + row.name() + ":" + chip.target().key(), drawn, h)) {
            state.openGroups();
        }
        if (ImGui.isItemHovered()) {
            draw.addRect(x + 0.5f, y + 0.5f, x + drawn - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER_HOVER,
                    w.m().radiusSmall());
            ImGui.setTooltip("Group " + chip.label() + ": open Groups");
        }
    }

    private static String subOf(ManagementRow row, Target target) {
        return row.targets().stream().filter(t -> t.target().equals(target)).map(TargetRow::sub)
                .findFirst().orElse("");
    }

    // ── Loop and actions ───────────────────────────────────────────────────

    private void paintLoop(ImDrawList draw, ManagementRow row, Columns c, float y, float h) {
        Controls ui = w.ui();
        ImFont mono = ui.fonts().monoCaption();
        String value = row.health().loopText();
        boolean hasUnit = !ManagementText.NONE.equals(value);
        String unit = hasUnit ? " ms" : "";
        float right = c.loop() + c.loopW();
        float unitW = ui.width(mono, unit);
        ui.textCentredY(draw, mono, right - unitW, y, h, ImGuiTheme.COL_FG3, unit);
        ui.textCentredY(draw, mono, right - unitW - ui.width(mono, value), y, h,
                hasUnit ? ImGuiTheme.COL_FG : ImGuiTheme.COL_FG2, value);
    }

    private void actions(ManagementRow row, ManagementState state, Columns c, float y, float h) {
        ImGuiTheme.Metrics m = w.m();
        float s = m.controlSmallHeight();
        float by = y + (h - s) * 0.5f;
        float step = s + m.u(0.5f);
        String name = row.name();
        boolean isRunning = row.health().state().isRunning();
        float x = c.actions() + (isRunning ? 0f : step);
        ImGui.setCursorScreenPos(x, by);
        boolean hasSettings = row.about().settingsCount() > 0 || row.about().hasUi();
        if (w.iconButton("##cfg:" + name, Icons.SLIDERS, ImGuiTheme.COL_FG2, ImGuiTheme.COL_ELEVATED,
                hasSettings ? "Settings for " + name : name + " has no settings", hasSettings)) {
            state.select(name);
            state.model().openSettings(name, Optional.empty());
        }
        ImGui.setCursorScreenPos(x + step, by);
        if (!isRunning) {
            if (w.iconButton("##start:" + name, Icons.PLAY, ImGuiTheme.COL_ACCENT, ImGuiTheme.COL_ACCENT_SOFT,
                    "Start " + name, true)) {
                state.model().start(name);
            }
            return;
        }
        if (w.iconButton("##stop:" + name, Icons.STOP, ImGuiTheme.COL_DANGER, ImGuiTheme.COL_DANGER_SOFT,
                "Stop " + name, true)) {
            state.model().stop(name);
        }
        ImGui.setCursorScreenPos(x + step * 2f, by);
        if (w.iconButton("##restart:" + name, Icons.REDO, ImGuiTheme.COL_FG2, ImGuiTheme.COL_ELEVATED,
                "Restart " + name, true)) {
            state.model().restart(name);
        }
    }
}
