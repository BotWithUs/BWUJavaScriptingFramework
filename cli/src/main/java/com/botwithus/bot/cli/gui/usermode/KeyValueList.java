package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;

import imgui.ImDrawList;
import imgui.ImFont;

import java.util.List;

/** The picker details pane's aligned "Key   value" list. */
final class KeyValueList {

    private static final float KV_LINE = 1.95f;

    /** One line of the list. */
    record Row(String key, String value) {}

    private KeyValueList() {}

    /** Draws {@code rows} from (x, y) with the values aligned; returns the y below the last one. */
    static float draw(Controls ui, ImDrawList draw, List<Row> rows, float x, float y) {
        ImFont key = ui.fonts().caption();
        ImFont value = ui.fonts().monoCaption();
        float keyW = 0f;
        for (Row r : rows) {
            keyW = Math.max(keyW, ui.width(key, r.key()));
        }
        float lineH = key.getFontSize() * KV_LINE;
        float cy = y;
        for (Row r : rows) {
            ui.text(draw, key, x, cy, ImGuiTheme.COL_FG2, r.key());
            ui.text(draw, value, x + keyW + ui.m().u(4), cy, ImGuiTheme.COL_FG, r.value());
            cy += lineH;
        }
        return cy;
    }
}
