package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;

import imgui.ImDrawList;
import imgui.ImFont;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The left half of a settings row: the label, the one-line description wrapped
 * to fit with the config key after it in mono, and a refused value's reason in
 * the danger colour. Measured before it is drawn so the row can be sized and
 * its control centred beside it.
 */
final class RowText {

    private static final float LABEL_LINE = 1.45f;
    private static final float CAPTION_LINE = 1.5f;
    private static final String SPACE = " ";

    /** One wrapped line of the description; {@code key} is set on the line the key ends up on. */
    private record Line(String text, String key) {}

    private final Controls ui;
    private final String label;
    private final List<Line> lines;
    private final List<String> errorLines;

    /**
     * @param key   the config key shown in mono after the description, or empty for none
     * @param error the reason a value was refused, if one was
     */
    RowText(Controls ui, String label, String description, String key, Optional<String> error, float width) {
        this.ui = ui;
        this.label = label;
        this.lines = layout(ui, description, key, width);
        this.errorLines = error.map(e -> ui.wrap(ui.fonts().caption(), e, width)).orElse(List.of());
    }

    float height() {
        return labelLine() + captionLine() * (lines.size() + errorLines.size());
    }

    /** Draws the block with its top-left at (x, y). */
    void draw(ImDrawList draw, float x, float y) {
        ui.text(draw, ui.fonts().smallMedium(), x, y, ImGuiTheme.COL_FG, label);
        float cy = y + labelLine();
        ImFont caption = ui.fonts().caption();
        for (Line line : lines) {
            ui.text(draw, caption, x, cy, ImGuiTheme.COL_FG2, line.text());
            if (!line.key().isEmpty()) {
                float kx = line.text().isEmpty() ? x : x + ui.width(caption, line.text() + SPACE);
                ui.text(draw, ui.fonts().monoCaption(), kx, cy, ImGuiTheme.COL_FG3, line.key());
            }
            cy += captionLine();
        }
        for (String line : errorLines) {
            ui.text(draw, caption, x, cy, ImGuiTheme.COL_DANGER, line);
            cy += captionLine();
        }
    }

    private float labelLine() {
        return ui.fonts().smallMedium().getFontSize() * LABEL_LINE;
    }

    private float captionLine() {
        return ui.fonts().caption().getFontSize() * CAPTION_LINE;
    }

    private static List<Line> layout(Controls ui, String description, String key, float width) {
        List<Line> out = new ArrayList<>();
        List<String> wrapped = description.isEmpty() ? List.of() : ui.wrap(ui.fonts().caption(), description, width);
        for (String text : wrapped) {
            out.add(new Line(text, ""));
        }
        if (key.isEmpty()) {
            return out;
        }
        float keyW = ui.width(ui.fonts().monoCaption(), key);
        if (!out.isEmpty()) {
            Line last = out.getLast();
            if (ui.width(ui.fonts().caption(), last.text() + SPACE) + keyW <= width) {
                out.set(out.size() - 1, new Line(last.text(), key));
                return out;
            }
        }
        out.add(new Line("", key));
        return out;
    }
}
