package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Segment;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.ImGuiTheme;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Draws one section's rows inside their rounded box: label and description on
 * the left, one control on the right, a hairline between rows. The accounts and
 * config-key tables are drawn by {@link SettingsTables}.
 */
final class SettingsRows {

    /** 220 px at 100%: the right-hand control column. */
    static final float CONTROL_COLUMN_EM = 14.67f;
    private static final float NUMBER_BOX_EM = 9.33f;
    private static final float TEXT_BOX_EM = 10.67f;
    private static final float CHART_HEIGHT_EM = 2.267f;
    private static final float CHART_LABEL_GAP_EM = 0.267f;
    private static final float BAR_GAP_PX = 3f;
    private static final float BAR_TOP_PX = 2f;
    private static final float BAR_RADIUS_PX = 2f;
    private static final float BAR_MIN_FRACTION = 0.08f;
    private static final int BOX_CHANNELS = 2;
    private static final int BOX_CHANNEL = 0;
    private static final int CONTENT_CHANNEL = 1;
    static final String ROW_ID = "row:";

    /** Where a row's control goes, once the row is sized. */
    private record Slot(float height, float x, float y) {}

    private final Controls ui;
    private final SettingsWidgets widgets;
    private final RowEdits edits;
    private final SettingsTables tables;

    SettingsRows(Controls ui, SettingsWidgets widgets, RowEdits edits, SettingsTables tables) {
        this.ui = ui;
        this.widgets = widgets;
        this.edits = edits;
        this.tables = tables;
    }

    /** Draws {@code items} in their box at the cursor, {@code width} wide, and moves the cursor below it. */
    void render(List<SettingsItem> items, float width, SettingsModel model) {
        ImGuiTheme.Metrics m = ui.m();
        float x = ImGui.getCursorScreenPosX();
        float top = ImGui.getCursorScreenPosY();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.channelsSplit(BOX_CHANNELS);
        draw.channelsSetCurrent(CONTENT_CHANNEL);
        float y = top;
        for (int i = 0; i < items.size(); i++) {
            y += item(items.get(i), x, y, width, model);
            if (i < items.size() - 1) {
                draw.addLine(x, y, x + width, y, ImGuiTheme.COL_BORDER, m.hairline());
            }
        }
        draw.channelsSetCurrent(BOX_CHANNEL);
        draw.addRectFilled(x, top, x + width, y, ImGuiTheme.COL_SURFACE, m.radiusLarge());
        draw.addRect(x + 0.5f, top + 0.5f, x + width - 0.5f, y - 0.5f, ImGuiTheme.COL_BORDER, m.radiusLarge());
        draw.channelsMerge();
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, 0f);
    }

    /** Draws one item with its top-left at (x, y); returns its height. */
    private float item(SettingsItem item, float x, float y, float width, SettingsModel model) {
        return switch (item) {
            case SettingsItem.KeyRow row -> keyRow(row, x, y, width, model);
            case SettingsItem.WaitPreview preview -> preview(preview.preview(), x, y, width);
            case SettingsItem.Accounts accounts -> tables.accounts(accounts.rows(), x, y, width, model);
            case SettingsItem.PlaceRow place -> placeRow(place, x, y, width, model);
            case SettingsItem.ActionRow action -> actionRow(action, x, y, width, model);
            case SettingsItem.RawKeys keys -> tables.rawKeys(keys.rows(), x, y, width, model);
        };
    }

    private float keyRow(SettingsItem.KeyRow row, float x, float y, float width, SettingsModel model) {
        String id = ROW_ID + row.name();
        RowText text = new RowText(ui, row.label(), row.description(), row.name(), edits.error(id),
                textWidth(width));
        Slot slot = layOut(text, x, y, width, ui.m().controlHeight());
        control(row, id, slot, model);
        return slot.height();
    }

    private void control(SettingsItem.KeyRow row, String id, Slot slot, SettingsModel model) {
        float columnW = controlColumn();
        float h = ui.m().controlHeight();
        ImGui.setCursorScreenPos(slot.x(), slot.y());
        switch (row.control()) {
            case RowControl.Switch s -> {
                ImGui.setCursorScreenPos(slot.x() + columnW - ui.toggleWidth(), slot.y());
                if (widgets.toggle(id, s.isOn())) {
                    edits.accept(id, model.edit(row.name(), String.valueOf(!s.isOn())));
                }
            }
            case RowControl.NumberBox n ->
                    textBox(id, row.name(), n.text(), n.unit(), NUMBER_BOX_EM, slot, model::edit);
            case RowControl.TextField t ->
                    textBox(id, row.name(), t.text(), "", TEXT_BOX_EM, slot, model::edit);
            case RowControl.Segments s -> {
                int clicked = ui.segmented("##" + id, labels(s.options()), s.selected(), columnW, h);
                if (clicked >= 0 && clicked != s.selected()) {
                    edits.accept(id, model.edit(row.name(), s.options().get(clicked).value()));
                }
            }
            case RowControl.Dropdown d -> {
                ImInt picked = new ImInt(d.selected());
                List<String> labels = d.options().stream().map(RowControl.Option::label).toList();
                if (ui.select("##" + id, picked, labels, columnW) && picked.get() != d.selected()) {
                    edits.accept(id, model.edit(row.name(), d.options().get(picked.get()).value()));
                }
            }
        }
    }

    /** A text box right-aligned in the control column; commits through {@code commit}. */
    private void textBox(String id, String name, String current, String unit, float widthEm, Slot slot,
                         BiFunction<String, String, EditResult> commit) {
        float w = ui.m().fontSize() * widthEm;
        ImGui.setCursorScreenPos(slot.x() + controlColumn() - w, slot.y());
        ImString buffer = edits.buffer(id, current);
        SettingsWidgets.Field field = widgets.field(id, buffer, unit, w, ui.m().controlHeight(),
                edits.error(id).isPresent());
        edits.track(id, field.isActive());
        if (field.isCommitted()) {
            edits.accept(id, commit.apply(name, buffer.get()));
        }
    }

    /** Puts {@code text} in the named row's box and commits it, as Enter would. */
    void commit(String name, String text, SettingsModel model) {
        String id = ROW_ID + name;
        edits.buffer(id, text).set(text);
        edits.accept(id, model.edit(name, text));
    }

    private float placeRow(SettingsItem.PlaceRow place, float x, float y, float width, SettingsModel model) {
        RowText text = new RowText(ui, place.label(), place.description(), place.path(), Optional.empty(),
                textWidth(width));
        return buttonRow(text, x, y, width, "##open-" + place.place(), Icons.FOLDER_OPEN, "Open", Tone.GHOST,
                () -> model.open(place.place()));
    }

    private float actionRow(SettingsItem.ActionRow action, float x, float y, float width, SettingsModel model) {
        RowText text = new RowText(ui, action.label(), action.description(), "", Optional.empty(),
                textWidth(width));
        Tone tone = action.action().isDestructive() ? Tone.STOP : Tone.GHOST;
        return buttonRow(text, x, y, width, "##run-" + action.action(), action.action().icon(),
                action.action().button(), tone, () -> model.run(action.action()));
    }

    private float buttonRow(RowText text, float x, float y, float width, String id, String icon, String label,
                            Tone tone, Runnable onClick) {
        float h = ui.m().controlSmallHeight();
        Slot slot = layOut(text, x, y, width, h);
        ImGui.setCursorScreenPos(slot.x() + controlColumn() - ui.buttonWidth(icon, label, tone), slot.y());
        if (ui.button(id, icon, label, tone, true, h)) {
            onClick.run();
        }
        return slot.height();
    }

    /** The reconnect wait chart: the summary sentence, one bar per try, "try 1" … "try N" under it. */
    private float preview(ReconnectPreview preview, float x, float y, float width) {
        ImGuiTheme.Metrics m = ui.m();
        float padX = m.u(4);
        float inner = width - padX * 2f;
        RowText text = new RowText(ui, "Preview", preview.summary(), "", Optional.empty(), inner);
        ImDrawList draw = ImGui.getWindowDrawList();
        float cy = y + m.u(3);
        text.draw(draw, x + padX, cy);
        cy += text.height() + m.u(2);
        float chartH = m.fontSize() * CHART_HEIGHT_EM;
        bars(draw, preview, x + padX, cy, inner, chartH);
        cy += chartH + m.fontSize() * CHART_LABEL_GAP_EM;
        List<Long> waits = preview.waitsMs();
        String last = "try " + waits.size();
        ui.text(draw, ui.fonts().monoCaption(), x + padX, cy, ImGuiTheme.COL_FG3, "try 1");
        ui.text(draw, ui.fonts().monoCaption(), x + padX + inner - ui.width(ui.fonts().monoCaption(), last), cy,
                ImGuiTheme.COL_FG3, last);
        cy += ui.fonts().monoCaption().getFontSize() + m.u(3);
        return cy - y;
    }

    private static void bars(ImDrawList draw, ReconnectPreview preview, float x, float y, float w, float h) {
        List<Long> waits = preview.waitsMs();
        if (waits.isEmpty()) {
            return;
        }
        float barW = (w - BAR_GAP_PX * (waits.size() - 1)) / waits.size();
        long longest = preview.longestMs();
        float bx = x;
        for (long wait : waits) {
            float fraction = longest <= 0L ? BAR_MIN_FRACTION : Math.max(BAR_MIN_FRACTION, (float) wait / longest);
            float top = y + h - h * fraction;
            draw.addRectFilled(bx, top, bx + barW, y + h, ImGuiTheme.COL_WARN_SOFT, BAR_RADIUS_PX);
            draw.addRectFilled(bx, top, bx + barW, top + BAR_TOP_PX, ImGuiTheme.COL_WARN, BAR_RADIUS_PX);
            bx += barW + BAR_GAP_PX;
        }
    }

    /** Sizes a row around {@code text} and a control {@code controlHeight} tall, and draws the text. */
    private Slot layOut(RowText text, float x, float y, float width, float controlHeight) {
        ImGuiTheme.Metrics m = ui.m();
        float h = Math.max(controlHeight, text.height()) + m.u(3) * 2f;
        text.draw(ImGui.getWindowDrawList(), x + m.u(4), y + (h - text.height()) * 0.5f);
        return new Slot(h, x + width - m.u(4) - controlColumn(), y + (h - controlHeight) * 0.5f);
    }

    private float textWidth(float width) {
        ImGuiTheme.Metrics m = ui.m();
        return width - m.u(4) * 2f - m.u(5) - controlColumn();
    }

    private float controlColumn() {
        return ui.m().fontSize() * CONTROL_COLUMN_EM;
    }

    private static List<Segment> labels(List<RowControl.Option> options) {
        return options.stream().map(o -> Segment.of(o.label())).toList();
    }
}
