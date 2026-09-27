package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.ArrayList;
import java.util.List;

/**
 * The "Failed to load" section: one card per JAR that did not load (red) or
 * that a newer JAR shadows (amber), with its error, an optional stack trace,
 * Reload, and a hint under the card when the page can tell what to do.
 */
final class FailedLoadsPainter {

    private static final String TITLE = "Failed to load";
    private static final int MAX_TRACE_LINES = 14;
    private static final float TRACE_LINE_HEIGHT = 1.5f;

    private final InstalledWidgets w;

    FailedLoadsPainter(InstalledWidgets widgets) {
        this.w = widgets;
    }

    /** Draws the section from (x, y), {@code width} wide; returns its height. */
    float render(InstalledView view, InstalledState state, float x, float y, float width) {
        Controls ui = w.ui();
        ImDrawList draw = ImGui.getWindowDrawList();
        ui.text(draw, ui.fonts().smallMedium(), x, y, ImGuiTheme.COL_DANGER, TITLE);
        float countX = x + ui.width(ui.fonts().smallMedium(), TITLE) + w.m().u(2);
        ui.text(draw, ui.fonts().monoCaption(), countX, y + w.m().hairline(), ImGuiTheme.COL_FG2,
                Integer.toString(view.problems().size()));
        float cy = y + ui.fonts().smallMedium().getFontSize() + w.m().u(2);
        for (LoadProblem p : view.problems()) {
            cy += card(p, state, x, cy, width);
            cy += hint(p, x, cy, width) + w.m().u(3);
        }
        return cy - y;
    }

    private float card(LoadProblem p, InstalledState state, float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        boolean isOpen = p.hasStackTrace() && state.isTraceOpen(p.jar());
        float headH = m.u(3) * 2f + m.iconTile();
        float traceH = isOpen ? traceHeight(p) + m.u(3) : 0f;
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + headH + traceH, ImGuiTheme.COL_SURFACE, m.radiusLarge());
        draw.addRect(x + 0.5f, y + 0.5f, x + width - 0.5f, y + headH + traceH - 0.5f, ImGuiTheme.COL_BORDER,
                m.radiusLarge());
        float actionsW = actions(p, state, x + width - m.u(3), y + (headH - m.controlSmallHeight()) * 0.5f);
        paintHead(draw, p, x + m.u(4), y + m.u(3), width - m.u(4) - m.u(3) * 2f - actionsW);
        if (isOpen) {
            float tx = x + m.u(4) + m.iconTile() + m.u(3);
            paintTrace(draw, p, tx, y + headH, x + width - m.u(3) - tx);
        }
        return headH + traceH;
    }

    private void paintHead(ImDrawList draw, LoadProblem p, float x, float y, float width) {
        Controls ui = w.ui();
        ImGuiTheme.Metrics m = w.m();
        boolean isFailure = p.kind() == LoadProblem.Kind.FAILED;
        ui.iconTile(draw, x, y, m.iconTile(), isFailure ? Icons.FILE_XMARK : Icons.WARNING,
                isFailure ? ImGuiTheme.COL_DANGER : ImGuiTheme.COL_WARN,
                isFailure ? ImGuiTheme.COL_DANGER_SOFT : ImGuiTheme.COL_WARN_SOFT);
        float tx = x + m.iconTile() + m.u(3);
        float textW = width - m.iconTile() - m.u(3);
        ImFont name = ui.fonts().monoSmall();
        ImFont msg = ui.fonts().monoCaption();
        float top = y + (m.iconTile() - name.getFontSize() - msg.getFontSize() - m.u(0.5f)) * 0.5f;
        ui.text(draw, name, tx, top, ImGuiTheme.COL_FG, ui.ellipsize(name, p.jarName(), textW));
        ui.text(draw, msg, tx, top + name.getFontSize() + m.u(0.5f),
                isFailure ? ImGuiTheme.COL_DANGER : ImGuiTheme.COL_WARN, ui.ellipsize(msg, p.message(), textW));
    }

    /** Stack trace toggle and Reload, right-aligned to {@code right}; returns their width. */
    private float actions(LoadProblem p, InstalledState state, float right, float y) {
        ImGuiTheme.Metrics m = w.m();
        float bh = m.controlSmallHeight();
        String id = "##fail:" + p.jar();
        float reloadW = w.ui().buttonWidth(Icons.ROTATE, "Reload", Tone.GHOST);
        boolean isOpen = state.isTraceOpen(p.jar());
        String traceLabel = isOpen ? "Hide" : "Stack trace";
        String traceIcon = isOpen ? InstalledWidgets.ANGLE_UP : Icons.ANGLE_DOWN;
        float traceW = p.hasStackTrace() ? w.linkWidth(traceIcon, traceLabel) + m.u(0.5f) : 0f;
        float x = right - reloadW - traceW;
        if (p.hasStackTrace()) {
            ImGui.setCursorScreenPos(x, y);
            if (w.link(id + ":trace", traceIcon, traceLabel, bh)) {
                state.toggleTrace(p.jar());
            }
        }
        ImGui.setCursorScreenPos(right - reloadW, y);
        if (w.ui().button(id + ":reload", Icons.ROTATE, "Reload", Tone.GHOST, true, bh)) {
            state.model().reload();
        }
        return reloadW + traceW;
    }

    private float lineHeight() {
        return w.ui().fonts().monoCaption().getFontSize() * TRACE_LINE_HEIGHT;
    }

    private float traceHeight(LoadProblem p) {
        return w.m().u(2) * 2f + lineHeight() * traceLines(p).size();
    }

    /** The trace's first lines, and how many more there are when it is longer. */
    private static List<String> traceLines(LoadProblem p) {
        List<String> all = p.stackTrace().lines().toList();
        if (all.size() <= MAX_TRACE_LINES) {
            return all;
        }
        List<String> head = new ArrayList<>(all.subList(0, MAX_TRACE_LINES - 1));
        head.add("… " + (all.size() - head.size()) + " more lines");
        return head;
    }

    private void paintTrace(ImDrawList draw, LoadProblem p, float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        Controls ui = w.ui();
        float h = traceHeight(p);
        draw.addRectFilled(x, y, x + width, y + h, ImGuiTheme.COL_BG, m.radius());
        draw.addRect(x + 0.5f, y + 0.5f, x + width - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER, m.radius());
        ImFont font = ui.fonts().monoCaption();
        float ly = y + m.u(2);
        boolean isFirst = true;
        for (String line : traceLines(p)) {
            String shown = ui.ellipsize(font, line.replace("\t", "  "), width - m.u(3) * 2f);
            ui.text(draw, font, x + m.u(3), ly, isFirst ? ImGuiTheme.COL_DANGER : ImGuiTheme.COL_FG2, shown);
            ly += lineHeight();
            isFirst = false;
        }
    }

    /** The hint under a card, wrapped; returns its height, zero when there is none. */
    private float hint(LoadProblem p, float x, float y, float width) {
        if (p.hint().isEmpty()) {
            return 0f;
        }
        Controls ui = w.ui();
        ImFont font = ui.fonts().caption();
        float lh = font.getFontSize() * TRACE_LINE_HEIGHT;
        float ly = y + w.m().u(1.5f);
        for (String line : ui.wrap(font, p.hint().get(), width)) {
            ui.text(ImGui.getWindowDrawList(), font, x, ly, ImGuiTheme.COL_FG2, line);
            ly += lh;
        }
        return ly - y;
    }
}
