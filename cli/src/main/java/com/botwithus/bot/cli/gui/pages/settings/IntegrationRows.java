package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.settings.AlertSettingKeys;
import com.botwithus.bot.cli.settings.SettingKey;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.type.ImString;

import java.util.List;
import java.util.Optional;

/**
 * Draws the Integrations section's own items: service cards (through
 * {@link ServiceCards}), the event grid, the quiet-hours row and the section's
 * notices. Its plain rows, bursts and summary time, are drawn by
 * {@link SettingsRows} like any other.
 */
final class IntegrationRows {

    /** 100 px at 100%: one service's column of the grid. */
    private static final float GRID_COLUMN_EM = 6.67f;
    private static final float GRID_HEADER_EM = 2.267f;
    private static final float GRID_ROW_EM = 2.533f;
    private static final float TIME_BOX_EM = 4.67f;
    private static final float CAPTION_LINE = 1.5f;
    private static final String GRID_ID = "grid:";
    private static final String QUIET_ID = "quiet:";
    private static final String QUIET_KEYS = "alerts.quiet.*";
    private static final String SEND = "Send";
    private static final String DETAIL_SEPARATOR = " · ";
    private static final String DASH = "–";

    private final Controls ui;
    private final SettingsWidgets widgets;
    private final RowEdits edits;
    private final ServiceCards cards;

    IntegrationRows(Controls ui, SettingsWidgets widgets, RowEdits edits, SecretFields secrets) {
        this.ui = ui;
        this.widgets = widgets;
        this.edits = edits;
        this.cards = new ServiceCards(ui, widgets, edits, secrets);
    }

    float card(SettingsItem.ServiceCard card, float x, float y, float width, SettingsModel model) {
        return cards.card(card, x, y, width, model);
    }

    // ── Event grid ─────────────────────────────────────────────────────────

    /** Draws the grid with its top-left at (x, y); returns its height. */
    float grid(SettingsItem.EventGrid grid, float x, float y, float width, SettingsModel model) {
        ImGuiTheme.Metrics m = ui.m();
        float colW = m.fontSize() * GRID_COLUMN_EM;
        float firstCol = x + width - m.u(4) - colW * grid.columns().size();
        float headerH = m.fontSize() * GRID_HEADER_EM;
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont head = ui.fonts().captionMedium();
        ui.textCentredY(draw, head, x + m.u(4), y, headerH, ImGuiTheme.COL_FG2, SEND);
        for (int i = 0; i < grid.columns().size(); i++) {
            GridColumn column = grid.columns().get(i);
            float cx = firstCol + colW * i + (colW - ui.width(head, column.label())) * 0.5f;
            ui.textCentredY(draw, head, cx, y, headerH,
                    column.isEnabled() ? ImGuiTheme.COL_FG2 : ImGuiTheme.COL_FG3, column.label());
        }
        float cy = y + headerH;
        for (GridRow row : grid.rows()) {
            draw.addLine(x, cy, x + width, cy, ImGuiTheme.COL_BORDER, m.hairline());
            cy += gridRow(row, x, cy, firstCol, colW, model);
        }
        return cy - y;
    }

    private float gridRow(GridRow row, float x, float y, float firstCol, float colW, SettingsModel model) {
        ImGuiTheme.Metrics m = ui.m();
        float h = m.fontSize() * GRID_ROW_EM;
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont label = ui.fonts().small();
        ImFont caption = ui.fonts().caption();
        float lx = x + m.u(4);
        ui.textCentredY(draw, label, lx, y, h, ImGuiTheme.COL_FG, row.label());
        if (!row.detail().isEmpty()) {
            float dx = lx + ui.width(label, row.label());
            String detail = ui.ellipsize(caption, DETAIL_SEPARATOR + row.detail(), Math.max(0f, firstCol - m.u(3) - dx));
            ui.textCentredY(draw, caption, dx, y, h, ImGuiTheme.COL_FG2, detail);
        }
        float box = widgets.tickBoxSize();
        for (int i = 0; i < row.cells().size(); i++) {
            GridRow.Cell cell = row.cells().get(i);
            ImGui.setCursorScreenPos(firstCol + colW * i + (colW - box) * 0.5f, y + (h - box) * 0.5f);
            String id = GRID_ID + cell.keyName();
            if (widgets.tickBox("##" + id, cell.isTicked(), cell.isEnabled())) {
                edits.accept(id, model.edit(cell.keyName(), String.valueOf(!cell.isTicked())));
            }
        }
        return h;
    }

    // ── Quiet hours ────────────────────────────────────────────────────────

    /** "Quiet hours" with its from and to boxes and its switch; returns the row's height. */
    float quietHours(SettingsItem.QuietHours row, float x, float y, float width, SettingsModel model) {
        ImGuiTheme.Metrics m = ui.m();
        float columnW = m.fontSize() * SettingsRows.CONTROL_COLUMN_EM;
        String fromId = QUIET_ID + AlertSettingKeys.QUIET_FROM.name();
        String toId = QUIET_ID + AlertSettingKeys.QUIET_TO.name();
        Optional<String> error = edits.error(fromId).or(() -> edits.error(toId));
        RowText text = new RowText(ui, row.label(), row.description(), QUIET_KEYS, error,
                width - m.u(4) * 2f - m.u(5) - columnW);
        float ctlH = m.controlHeight();
        float h = Math.max(ctlH, text.height()) + m.u(3) * 2f;
        text.draw(ImGui.getWindowDrawList(), x + m.u(4), y + (h - text.height()) * 0.5f);
        float right = x + width - m.u(4);
        float cy = y + (h - ctlH) * 0.5f;
        String switchId = QUIET_ID + AlertSettingKeys.QUIET_ENABLED.name();
        ImGui.setCursorScreenPos(right - ui.toggleWidth(), cy);
        if (widgets.toggle(switchId, row.isOn())) {
            edits.accept(switchId, model.edit(AlertSettingKeys.QUIET_ENABLED.name(), String.valueOf(!row.isOn())));
        }
        float boxW = m.fontSize() * TIME_BOX_EM;
        float toX = right - ui.toggleWidth() - m.u(4) - boxW;
        float dashW = ui.width(ui.fonts().small(), DASH) + m.u(2) * 2f;
        timeBox(toId, AlertSettingKeys.QUIET_TO, row.to(), toX, cy, boxW, model);
        ui.textCentredY(ImGui.getWindowDrawList(), ui.fonts().small(), toX - dashW + m.u(2), cy, ctlH,
                ImGuiTheme.COL_FG2, DASH);
        timeBox(fromId, AlertSettingKeys.QUIET_FROM, row.from(), toX - dashW - boxW, cy, boxW, model);
        return h;
    }

    private void timeBox(String id, SettingKey<String> key, String value, float x, float y, float w,
                         SettingsModel model) {
        ImGui.setCursorScreenPos(x, y);
        ImString buffer = edits.buffer(id, value);
        SettingsWidgets.Field field = widgets.field(id, buffer, "", w, ui.m().controlHeight(),
                edits.error(id).isPresent());
        edits.track(id, field.isActive());
        if (field.isCommitted()) {
            edits.accept(id, model.edit(key.name(), buffer.get()));
        }
    }

    // ── Notice ─────────────────────────────────────────────────────────────

    /** A line the section wants read, behind a warning or info mark; returns its height. */
    float notice(SettingsItem.Notice notice, float x, float y, float width) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont caption = ui.fonts().caption();
        float line = caption.getFontSize() * CAPTION_LINE;
        String icon = notice.isWarning() ? Icons.WARNING : Icons.INFO;
        int iconCol = notice.isWarning() ? ImGuiTheme.COL_WARN : ImGuiTheme.COL_FG2;
        float iconW = ui.width(caption, icon) + m.u(2);
        List<String> lines = ui.wrap(caption, notice.text(), width - m.u(4) * 2f - iconW);
        float h = lines.size() * line + m.u(3) * 2f;
        float cy = y + m.u(3);
        ui.textCentredY(draw, caption, x + m.u(4), cy, line, iconCol, icon);
        for (String text : lines) {
            ui.text(draw, caption, x + m.u(4) + iconW, cy + (line - caption.getFontSize()) * 0.5f,
                    ImGuiTheme.COL_FG, text);
            cy += line;
        }
        return h;
    }
}
