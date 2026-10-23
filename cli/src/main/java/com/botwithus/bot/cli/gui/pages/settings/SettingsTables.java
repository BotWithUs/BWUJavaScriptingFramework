package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.ImGuiTheme;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.type.ImString;

import java.util.List;

/**
 * The two tables on the Settings page: saved accounts (who resumes what) and
 * every key in {@code config.properties}. Both are drawn straight onto the draw
 * list in the page's token style rather than as ImGui tables.
 */
final class SettingsTables {

    private static final float HEADER_EM = 2f;
    private static final float ACCOUNT_ROW_EM = 2.933f;
    private static final float KEY_ROW_EM = 2.4f;
    private static final float AUTO_START_COLUMN_EM = 7.33f;
    private static final float FORGET_COLUMN_EM = 4f;
    private static final float VALUE_COLUMN_EM = 14.67f;
    private static final float ACCOUNT_SHARE = 0.4f;
    private static final float KEY_SHARE = 0.55f;
    private static final float EMPTY_ROW_EM = 3f;
    private static final String RAW_ID = "raw:";
    private static final String NOTHING_SAVED = "nothing saved";

    private final Controls ui;
    private final SettingsWidgets widgets;
    private final RowEdits edits;

    SettingsTables(Controls ui, SettingsWidgets widgets, RowEdits edits) {
        this.ui = ui;
        this.widgets = widgets;
        this.edits = edits;
    }

    // ── Accounts ───────────────────────────────────────────────────────────

    /** Draws the accounts table with its top-left at (x, y); returns its height. */
    float accounts(List<AccountRow> rows, float x, float y, float width, SettingsModel model) {
        ImGuiTheme.Metrics m = ui.m();
        if (rows.isEmpty()) {
            float h = m.fontSize() * EMPTY_ROW_EM;
            ui.textCentredY(ImGui.getWindowDrawList(), ui.fonts().small(), x + m.u(4), y, h, ImGuiTheme.COL_FG2,
                    "No accounts saved yet. One is added when a client has been identified.");
            return h;
        }
        float[] cols = accountColumns(x, width);
        float headerH = m.fontSize() * HEADER_EM;
        header(y, headerH, cols, "Account", "Resumes", "Auto-start");
        float cy = y + headerH;
        for (AccountRow row : rows) {
            cy += account(row, cols, x, cy, width, model);
        }
        return cy - y;
    }

    /** Left edges of the four columns: account, resumes, auto-start, forget. */
    private float[] accountColumns(float x, float width) {
        ImGuiTheme.Metrics m = ui.m();
        float fs = m.fontSize();
        float forget = x + width - m.u(4) - fs * FORGET_COLUMN_EM;
        float autoStart = forget - fs * AUTO_START_COLUMN_EM;
        float account = x + m.u(4);
        float resumes = account + (autoStart - account) * ACCOUNT_SHARE;
        return new float[] {account, resumes, autoStart, forget};
    }

    private float account(AccountRow row, float[] cols, float x, float y, float width, SettingsModel model) {
        ImGuiTheme.Metrics m = ui.m();
        float h = m.fontSize() * ACCOUNT_ROW_EM;
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont name = ui.fonts().small();
        ImFont uuid = ui.fonts().monoCaption();
        float textTop = y + (h - name.getFontSize() - uuid.getFontSize() - m.u(1)) * 0.5f;
        float nameW = cols[1] - cols[0] - m.u(3);
        ui.text(draw, name, cols[0], textTop, ImGuiTheme.COL_FG, ui.ellipsize(name, row.title(), nameW));
        ui.text(draw, uuid, cols[0], textTop + name.getFontSize() + m.u(1), ImGuiTheme.COL_FG2, row.shortUuid());
        chips(draw, row.scripts(), cols[1], y, h, cols[2] - cols[1] - m.u(3));
        ImGui.setCursorScreenPos(cols[2], y + (h - m.controlHeight()) * 0.5f);
        if (widgets.toggle("acc:" + row.uuid(), row.isAutoStart())) {
            model.setAutoStart(row.uuid(), !row.isAutoStart());
        }
        ImGui.setCursorScreenPos(cols[3], y + (h - m.controlHeight()) * 0.5f);
        if (ui.button("##forget-" + row.uuid(), Icons.ERASER, "", Tone.ICON, !row.scripts().isEmpty())) {
            model.forgetScripts(row.uuid());
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Forget saved scripts for " + row.title());
        }
        draw.addLine(x, y, x + width, y, ImGuiTheme.COL_BORDER, m.hairline());
        return h;
    }

    /** The scripts an account resumes, as outlined chips; "+N" when they do not all fit. */
    private void chips(ImDrawList draw, List<String> scripts, float x, float y, float h, float maxW) {
        ImGuiTheme.Metrics m = ui.m();
        ImFont font = ui.fonts().caption();
        float chipH = m.chipHeight();
        float top = y + (h - chipH) * 0.5f;
        if (scripts.isEmpty()) {
            chip(draw, x, top, NOTHING_SAVED, ImGuiTheme.COL_FG3);
            return;
        }
        float cx = x;
        for (int i = 0; i < scripts.size(); i++) {
            String label = scripts.get(i);
            float w = chipWidth(label);
            String more = "+" + (scripts.size() - i);
            if (cx + w > x + maxW && i > 0) {
                ui.textCentredY(draw, font, cx, top, chipH, ImGuiTheme.COL_FG2, more);
                return;
            }
            chip(draw, cx, top, label, ImGuiTheme.COL_FG);
            cx += w + m.u(1);
        }
    }

    private float chipWidth(String label) {
        return ui.width(ui.fonts().caption(), label) + ui.m().u(2) * 2f;
    }

    private void chip(ImDrawList draw, float x, float y, String label, int fg) {
        ImGuiTheme.Metrics m = ui.m();
        float w = chipWidth(label);
        float h = m.chipHeight();
        draw.addRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER, m.radiusSmall());
        ui.textCentredY(draw, ui.fonts().caption(), x + m.u(2), y, h, fg, label);
    }

    // ── All config keys ────────────────────────────────────────────────────

    /** Draws every config key with its value and where it is shown above; returns the height. */
    float rawKeys(List<RawKeyRow> rows, float x, float y, float width, SettingsModel model) {
        ImGuiTheme.Metrics m = ui.m();
        float valueW = m.fontSize() * VALUE_COLUMN_EM;
        float key = x + m.u(4);
        float value = key + (width - m.u(4) * 2f - valueW) * KEY_SHARE;
        float shownAs = value + valueW + m.u(3);
        float[] cols = {key, value, shownAs};
        float headerH = m.fontSize() * HEADER_EM;
        header(y, headerH, cols, "Key", "Value", "Shown above as");
        float cy = y + headerH;
        for (RawKeyRow row : rows) {
            cy += rawKey(row, cols, valueW, x, cy, width, model);
        }
        return cy - y;
    }

    private float rawKey(RawKeyRow row, float[] cols, float valueW, float x, float y, float width,
                         SettingsModel model) {
        ImGuiTheme.Metrics m = ui.m();
        float h = m.fontSize() * KEY_ROW_EM;
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont mono = ui.fonts().monoCaption();
        int keyCol = row.isEditable() ? ImGuiTheme.COL_FG : ImGuiTheme.COL_FG2;
        ui.textCentredY(draw, mono, cols[0], y, h, keyCol, ui.ellipsize(mono, row.name(), cols[1] - cols[0] - m.u(3)));
        String id = RAW_ID + row.name();
        if (row.isEditable()) {
            float fieldH = m.controlSmallHeight();
            ImGui.setCursorScreenPos(cols[1], y + (h - fieldH) * 0.5f);
            ImString buffer = edits.buffer(id, row.value());
            SettingsWidgets.Field field = widgets.field(id, buffer, "", valueW, fieldH, edits.error(id).isPresent());
            edits.track(id, field.isActive());
            if (field.isCommitted()) {
                edits.accept(id, model.editRaw(row.name(), buffer.get()));
            }
        } else {
            ui.textCentredY(draw, mono, cols[1], y, h, ImGuiTheme.COL_FG2, ui.ellipsize(mono, row.value(), valueW));
        }
        String shownAs = edits.error(id).orElse(row.shownAs());
        int shownCol = edits.error(id).isPresent() ? ImGuiTheme.COL_DANGER : ImGuiTheme.COL_FG3;
        ImFont caption = ui.fonts().caption();
        ui.textCentredY(draw, caption, cols[2], y, h, shownCol,
                ui.ellipsize(caption, shownAs, x + width - m.u(4) - cols[2]));
        draw.addLine(x, y, x + width, y, ImGuiTheme.COL_BORDER, m.hairline());
        return h;
    }

    // ── Shared ─────────────────────────────────────────────────────────────

    /** A table's column headings, one per left edge in {@code cols}. */
    private void header(float y, float h, float[] cols, String... titles) {
        ImDrawList draw = ImGui.getWindowDrawList();
        for (int i = 0; i < titles.length; i++) {
            ui.textCentredY(draw, ui.fonts().captionMedium(), cols[i], y, h, ImGuiTheme.COL_FG2, titles[i]);
        }
    }
}
