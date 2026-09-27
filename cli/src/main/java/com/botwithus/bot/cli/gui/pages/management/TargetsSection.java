package com.botwithus.bot.cli.gui.pages.management;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.type.ImInt;

import java.util.List;
import java.util.Optional;

/**
 * The Overview's "Applies to": each target with its own-settings count, a
 * settings button and a remove button; the confirm a running script's change
 * waits on; the add row (type, then which, then Add); and a note on what the
 * targets mean.
 */
final class TargetsSection {

    private static final float LINE = 1.35f;
    private static final float ICON_COL_EM = 1.6f;
    private static final float NOTE_LINE = 1.45f;
    private static final String NONE = "Not applied to anything. It won't act until you add a target.";
    private static final String NOTE_WHOLE_HOST = "Whole host covers every client, so other targets are"
            + " folded into it.";
    private static final String NOTE_TARGETS = "It only sees and controls what's listed. Groups show it as"
            + " their manager; client cards show a robot next to the script.";
    private static final String NOTE_TAIL = " Changes take effect on its next loop.";

    private final ManagementWidgets w;
    private final ImInt kind = new ImInt();
    private final ImInt pick = new ImInt();

    TargetsSection(ManagementWidgets widgets) {
        this.w = widgets;
    }

    /** Draws the section from (x, y), {@code width} wide; returns its height. */
    float render(ManagementView view, ManagementRow row, ManagementState state, float x, float y, float width) {
        ImDrawList draw = ImGui.getWindowDrawList();
        ImGuiTheme.Metrics m = w.m();
        w.heading(draw, x, y, "Applies to");
        float cy = y + w.ui().fonts().captionMedium().getFontSize() + m.u(2);
        cy += list(draw, row, state, x, cy, width) + m.u(2);
        Optional<ManagementState.PendingChange> pending = state.pendingFor(row.name());
        if (pending.isPresent()) {
            cy += confirm(draw, row, pending.get(), state, x, cy, width) + m.u(2);
        }
        cy += addRow(view, row, state, x, cy, width) + m.u(2);
        ImFont font = w.ui().fonts().caption();
        String note = (row.isWholeHost() ? NOTE_WHOLE_HOST : NOTE_TARGETS) + NOTE_TAIL;
        cy += w.paragraph(draw, font, x, cy, width, font.getFontSize() * NOTE_LINE, ImGuiTheme.COL_FG2, note);
        return cy - y;
    }

    // ── The list ───────────────────────────────────────────────────────────

    private float rowHeight() {
        Controls ui = w.ui();
        return w.m().u(1.5f) * 2f + (ui.fonts().small().getFontSize() + ui.fonts().caption().getFontSize()) * LINE;
    }

    private float list(ImDrawList draw, ManagementRow row, ManagementState state, float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        Controls ui = w.ui();
        float h = row.targets().isEmpty() ? ui.fonts().caption().getFontSize() * LINE + m.u(3) * 2f
                : rowHeight() * row.targets().size();
        draw.addRect(x + 0.5f, y + 0.5f, x + width - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER, m.radius());
        if (row.targets().isEmpty()) {
            ui.textCentredY(draw, ui.fonts().caption(), x + m.u(3), y, h, ImGuiTheme.COL_FG2,
                    ui.ellipsize(ui.fonts().caption(), NONE, width - m.u(6)));
            return h;
        }
        float ry = y;
        for (int i = 0; i < row.targets().size(); i++) {
            if (i > 0) {
                draw.addLine(x + 1f, ry, x + width - 1f, ry, ImGuiTheme.COL_BORDER, m.hairline());
            }
            target(draw, row, row.targets().get(i), state, x, ry, width);
            ry += rowHeight();
        }
        return h;
    }

    private void target(ImDrawList draw, ManagementRow row, TargetRow target, ManagementState state, float x,
                        float y, float width) {
        Controls ui = w.ui();
        ImGuiTheme.Metrics m = w.m();
        float h = rowHeight();
        float iconCol = w.fs() * ICON_COL_EM;
        String glyph = ManagementWidgets.iconOf(target.target());
        ImFont cap = ui.fonts().caption();
        ui.textCentredY(draw, cap, x + m.u(2) + (iconCol - ui.width(cap, glyph)) * 0.5f, y, h, ImGuiTheme.COL_FG2,
                glyph);
        float s = m.controlSmallHeight();
        float buttonsW = target.isWholeHost() ? s : s * 2f + m.u(0.5f);
        float tx = x + m.u(2) + iconCol + m.u(2);
        float textW = x + width - m.u(1) - buttonsW - m.u(2) - tx;
        float top = y + m.u(1.5f);
        float lineH = ui.fonts().small().getFontSize() * LINE;
        ui.textCentredY(draw, ui.fonts().small(), tx, top, lineH, ImGuiTheme.COL_FG,
                ui.ellipsize(ui.fonts().small(), target.label(), textW));
        sub(draw, target, tx, top + lineH, textW);
        buttons(row, target, state, x + width - m.u(1) - buttonsW, y + (h - s) * 0.5f);
    }

    /** "group · 4 clients · 2 own settings", the count in blue. */
    private void sub(ImDrawList draw, TargetRow target, float x, float y, float width) {
        Controls ui = w.ui();
        ImFont cap = ui.fonts().caption();
        float lineH = cap.getFontSize() * LINE;
        String sub = ui.ellipsize(cap, target.sub(), width);
        ui.textCentredY(draw, cap, x, y, lineH, ImGuiTheme.COL_FG2, sub);
        if (target.ownSettings() <= 0) {
            return;
        }
        float ox = x + ui.width(cap, sub + " · ");
        ui.textCentredY(draw, cap, x + ui.width(cap, sub), y, lineH, ImGuiTheme.COL_FG2, " · ");
        String own = ManagementText.count(target.ownSettings(), "own setting");
        ui.textCentredY(draw, cap, ox, y, lineH, ImGuiTheme.COL_INFO, ui.ellipsize(cap, own, x + width - ox));
    }

    private void buttons(ManagementRow row, TargetRow target, ManagementState state, float x, float y) {
        float s = w.m().controlSmallHeight();
        String id = row.name() + ":" + target.target().key();
        float bx = x;
        if (!target.isWholeHost()) {
            ImGui.setCursorScreenPos(bx, y);
            if (w.iconButton("##tset:" + id, Icons.SLIDERS, ImGuiTheme.COL_FG2, ImGuiTheme.COL_ELEVATED,
                    "Settings for " + target.label(), true)) {
                state.model().openSettings(row.name(), Optional.of(target.target()));
            }
            bx += s + w.m().u(0.5f);
        }
        ImGui.setCursorScreenPos(bx, y);
        if (w.iconButton("##trm:" + id, Icons.XMARK, ImGuiTheme.COL_FG2, ImGuiTheme.COL_DANGER_SOFT,
                "Stop applying to " + target.label(), true)) {
            state.requestChange(row, new TargetChange.Remove(target.target()), target.label());
        }
    }

    // ── A running script's change, waiting for its confirm ─────────────────

    private float confirm(ImDrawList draw, ManagementRow row, ManagementState.PendingChange pending,
                          ManagementState state, float x, float y, float width) {
        Controls ui = w.ui();
        ImGuiTheme.Metrics m = w.m();
        ImFont font = ui.fonts().caption();
        float lineH = font.getFontSize() * NOTE_LINE;
        String text = describe(pending) + " restarts " + row.name()
                + ", which is running, so it starts over on its new targets.";
        List<String> lines = ui.wrap(font, text, width - m.u(3) * 2f);
        float h = m.u(3) * 2f + lines.size() * lineH + m.u(2) + m.controlSmallHeight();
        draw.addRectFilled(x, y, x + width, y + h, ImGuiTheme.COL_INFO_SOFT, m.radius());
        w.paragraph(draw, font, x + m.u(3), y + m.u(3), width - m.u(3) * 2f, lineH, ImGuiTheme.COL_FG, text);
        ImGui.setCursorScreenPos(x + m.u(3), y + m.u(3) + lines.size() * lineH + m.u(2));
        String verb = switch (pending.change()) {
            case TargetChange.Add _ -> "Add and restart";
            case TargetChange.Remove _ -> "Remove and restart";
        };
        if (ui.button("##tchange-yes", null, verb, Tone.PRIMARY, true, m.controlSmallHeight())) {
            state.confirmChange();
        }
        ImGui.sameLine(0f, m.u(2));
        if (w.link("##tchange-no", null, "Cancel", m.controlSmallHeight())) {
            state.cancelChange();
        }
        return h;
    }

    /** "Adding Woodcutters" or "Removing Oakheart · Woodcutting". */
    private static String describe(ManagementState.PendingChange pending) {
        return switch (pending.change()) {
            case TargetChange.Add _ -> "Adding " + pending.label();
            case TargetChange.Remove _ -> "Removing " + pending.label();
        };
    }

    // ── Add row ────────────────────────────────────────────────────────────

    /** Type across the width, then which one and Add under it; returns its height. */
    private float addRow(ManagementView view, ManagementRow row, ManagementState state, float x, float y,
                         float width) {
        Controls ui = w.ui();
        ImGuiTheme.Metrics m = w.m();
        float h = m.controlHeight();
        ImGui.setCursorScreenPos(x, y);
        kind.set(state.addKind().ordinal());
        if (ui.select("##t-kind:" + row.name(), kind, TargetKind.labels(), width)) {
            state.setAddKind(TargetKind.values()[kind.get()]);
        }
        List<TargetChoices.Option> options = state.addOptions(row, view.choices());
        float addW = ui.buttonWidth(Icons.PLUS, "Add", Tone.GHOST);
        float itemY = y + h + m.u(2);
        ImGui.setCursorScreenPos(x, itemY);
        boolean hasOptions = !options.isEmpty();
        pick.set(hasOptions ? Math.min(state.addPick(), options.size() - 1) : 0);
        List<String> labels = hasOptions ? options.stream().map(TargetChoices.Option::label).toList()
                : List.of(TargetKind.NOTHING_LEFT);
        ImGui.beginDisabled(!hasOptions);
        if (ui.select("##t-item:" + row.name(), pick, labels, width - addW - m.u(2))) {
            state.setAddPick(pick.get());
        }
        ImGui.endDisabled();
        ImGui.setCursorScreenPos(x + width - addW, itemY);
        if (ui.button("##t-add:" + row.name(), Icons.PLUS, "Add", Tone.GHOST, hasOptions)) {
            state.setAddPick(pick.get());
            state.addPicked(row, view.choices());
        }
        return h * 2f + m.u(2);
    }
}
