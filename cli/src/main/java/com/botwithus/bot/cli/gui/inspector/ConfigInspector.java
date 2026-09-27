package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ConfigField.BoolField;
import com.botwithus.bot.api.config.ConfigField.ChoiceField;
import com.botwithus.bot.api.config.ConfigField.IntField;
import com.botwithus.bot.api.config.ConfigField.ItemIdField;
import com.botwithus.bot.api.config.ConfigField.StringField;
import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.cli.gui.CategoryStyle;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Segment;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.Motion;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ClientScript;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ManagementScript;
import com.botwithus.bot.cli.gui.inspector.SettingsFor.InheritedValue;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImInt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * The docked config inspector's contents: one script's settings and its own UI.
 * The shell docks it beside the page that owns the script, in either mode, and
 * only one is ever open; {@link InspectorState} says which.
 *
 * <p>The Settings tab edits the script's declared fields and applies them in one
 * go; changed fields carry an amber dot and the footer counts them. A management
 * script with targets gets a "Settings for" picker above its fields: the
 * defaults, or one target's own values. On a target, a field it does not set
 * says where its value comes from ("from defaults", "from Woodcutters"), and one
 * it does set says "own value" with a link back to the inherited one. The Script
 * UI tab, offered only when the script draws its own UI, frames that ImGui
 * without restyling it and has no footer: Apply and Reset act on the fields,
 * which are not on screen there.</p>
 */
final class ConfigInspector {

    private static final Logger log = LoggerFactory.getLogger(ConfigInspector.class);

    private static final int SEGMENTED_MAX_CHOICES = 3;
    private static final float TAB_HEIGHT_EM = 2.267f;
    private static final float TAB_UNDERLINE_PX = 2f;
    private static final float FIELD_GAP_EM = 0.4f;
    private static final float EMPTY_TOP_EM = 2.667f;
    private static final float DASH_EM = 0.267f;
    private static final float LINE = 1.35f;
    private static final float NAME_LINE = 1.25f;
    private static final float NAME_SHARE = 0.7f;
    /** The longest inherited value a "use …" link spells out before it is cut short. */
    private static final int LINK_VALUE_CHARS = 24;
    private static final String PICKER_LOCKED_NOTE = "Apply or reset your changes to pick another target.";

    private final Controls ui;
    private final InspectorState state;
    /** The picker's selection, reset from the target every frame. */
    private final ImInt pickerChoice = new ImInt();
    private ConfigEdits edits;
    private InspectorSubject editsFor;

    ConfigInspector(Controls ui, InspectorState state) {
        this.ui = ui;
        this.state = state;
    }

    /** The open form, or {@code null} before its first frame. The dev preview's seam. */
    ConfigEdits edits() {
        return edits;
    }

    /** Renders at full drawer width into the current (possibly narrower, clipping) child. */
    void render(InspectorTarget target, float width, float height) {
        ConfigEdits form = editsFor(target);
        InspectorTab tab = shownTab(target);
        float x = ImGui.getWindowPosX();
        float y = ImGui.getWindowPosY();
        float headerH = renderHeader(target, x, y, width);
        float tabsH = renderTabs(target, tab, x, y + headerH, width);
        float footerH = tab == InspectorTab.SETTINGS ? footerHeight() : 0f;
        ImGui.setCursorScreenPos(x, y + headerH + tabsH);
        renderBody(target, tab, form, width, height - headerH - tabsH - footerH);
        if (tab == InspectorTab.SETTINGS) {
            renderFooter(form, target, x, y + height - footerH, width);
        }
    }

    /** The tab to draw: Script UI falls back to Settings for a script that draws none. */
    private InspectorTab shownTab(InspectorTarget target) {
        return target.hasCustomUi() ? state.tab() : InspectorTab.SETTINGS;
    }

    /** The form for this subject, rebuilt when the subject or its fields change (a reload can do both). */
    private ConfigEdits editsFor(InspectorTarget target) {
        if (edits == null || !target.subject().equals(editsFor) || !target.fields().equals(edits.fields())) {
            edits = new ConfigEdits(target.fields(), target.current().get());
            editsFor = target.subject();
        } else {
            edits.sync(target.current().get());
        }
        return edits;
    }

    // ── Header + tabs ──────────────────────────────────────────────────────

    private float renderHeader(InspectorTarget target, float x, float y, float width) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float tile = m.iconTile();
        float top = y + m.u(4);
        ui.iconTile(draw, x + m.u(4), top, tile, iconOf(target), ImGuiTheme.COL_ACCENT, ImGuiTheme.COL_ACCENT_SOFT);
        float tx = x + m.u(4) + tile + m.u(3);
        float closeX = x + width - m.u(3) - m.controlHeight();
        float tw = closeX - m.u(3) - tx;
        ImFont nameFont = ui.fonts().bodyMedium();
        String name = ui.ellipsize(nameFont, target.script().name(), tw * NAME_SHARE);
        float textH = nameFont.getFontSize() * NAME_LINE + ui.fonts().small().getFontSize() * LINE;
        float nameY = top + (tile - textH) * 0.5f;
        ui.text(draw, nameFont, tx, nameY, ImGuiTheme.COL_FG, name);
        if (!target.script().version().isBlank()) {
            ImFont mono = ui.fonts().monoCaption();
            float vx = tx + ui.width(nameFont, name) + m.u(1.5f);
            ui.text(draw, mono, vx, nameY + nameFont.getFontSize() - mono.getFontSize(), ImGuiTheme.COL_FG2,
                    "v" + target.script().version());
        }
        ui.text(draw, ui.fonts().small(), tx, nameY + nameFont.getFontSize() * NAME_LINE, ImGuiTheme.COL_FG2,
                ui.ellipsize(ui.fonts().small(), target.context(), tw));
        ImGui.setCursorScreenPos(closeX, top + (tile - m.controlHeight()) * 0.5f);
        if (ui.button("##inspector-close", Icons.XMARK, "", Tone.ICON, true)) {
            state.close();
        }
        return m.u(4) + tile + m.u(3);
    }

    /** A client script shows its category; a management script, which has no client, the robot. */
    private static String iconOf(InspectorTarget target) {
        return switch (target.subject()) {
            case ClientScript ignored -> CategoryStyle.of(target.script().category()).icon();
            case ManagementScript ignored -> Icons.ROBOT;
        };
    }

    private float renderTabs(InspectorTarget target, InspectorTab shown, float x, float y, float width) {
        ImGuiTheme.Metrics m = ui.m();
        float h = ui.fonts().body().getFontSize() * TAB_HEIGHT_EM;
        ImDrawList draw = ImGui.getWindowDrawList();
        String id = target.subject().toString();
        float tx = x + m.u(4);
        tx += tabButton(draw, "Settings", InspectorTab.SETTINGS, shown, tx, y, h, id) + m.u(4);
        if (target.hasCustomUi()) {
            tabButton(draw, "Script UI", InspectorTab.SCRIPT_UI, shown, tx, y, h, id);
        }
        draw.addLine(x, y + h - 0.5f, x + width, y + h - 0.5f, ImGuiTheme.COL_BORDER, m.hairline());
        return h;
    }

    private float tabButton(ImDrawList draw, String label, InspectorTab which, InspectorTab shown,
                            float x, float y, float h, String id) {
        ImFont font = ui.fonts().smallMedium();
        float w = ui.width(font, label);
        ImGui.setCursorScreenPos(x, y);
        if (ImGui.invisibleButton("##tab-" + which + id, w, h)) {
            state.showTab(which);
        }
        float t = Motion.step("tab:" + which + id, ImGui.isItemHovered() ? 1f : 0f,
                1f / ImGuiTheme.DURATION_FAST_S);
        boolean on = shown == which;
        int col = on ? ImGuiTheme.COL_FG : Controls.lerp(ImGuiTheme.COL_FG2, ImGuiTheme.COL_FG, t);
        ui.textCentredY(draw, font, x, y, h, col, label);
        if (on) {
            draw.addRectFilled(x, y + h - TAB_UNDERLINE_PX, x + w, y + h, ImGuiTheme.COL_FG, 1f);
        }
        return w;
    }

    // ── Body ───────────────────────────────────────────────────────────────

    private void renderBody(InspectorTarget target, InspectorTab tab, ConfigEdits form, float width,
                            float height) {
        ImGuiTheme.Metrics m = ui.m();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, m.u(4), m.u(4));
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, m.u(2), m.u(4));
        ImGui.beginChild("##inspector-body", width, Math.max(0f, height), ImGuiChildFlags.AlwaysUseWindowPadding, 0);
        switch (tab) {
            case SETTINGS -> renderSettings(target, form);
            case SCRIPT_UI -> renderScriptUi(target);
        }
        ImGui.endChild();
        ImGui.popStyleVar(2);
    }

    private void renderSettings(InspectorTarget target, ConfigEdits form) {
        if (form.fields().isEmpty()) {
            renderNothing(target.script().name() + " has no settings.");
            return;
        }
        float w = ImGui.getContentRegionAvailX();
        SettingsFor picker = target.settingsFor();
        if (picker.isShown()) {
            renderPicker(picker, form, w);
        }
        for (ConfigField field : form.fields()) {
            ImGui.pushID(field.key());
            renderLabel(field, form.isDirty(field), w);
            renderControl(field, form, target, w);
            picker.inheritedOf(field.key()).ifPresent(inherited -> renderSource(field, form, inherited));
            ImGui.popID();
        }
        if (picker.selected().isPresent()) {
            renderUseInherited(form, picker);
        } else if (ui.button("##inspector-defaults", Icons.ROTATE, "Restore defaults", Tone.GHOST,
                !form.isAtDefaults())) {
            form.restoreDefaults();
        }
    }

    // ── Settings for ───────────────────────────────────────────────────────

    /**
     * The "Settings for" picker and the line under it. Picking reopens the
     * inspector on that target; with unsaved changes the picker is locked, so
     * switching can never throw an edit away.
     */
    private void renderPicker(SettingsFor picker, ConfigEdits form, float w) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImFont font = ui.fonts().small();
        float lineH = font.getFontSize() * LINE;
        ui.textCentredY(draw, font, x, y, lineH, ImGuiTheme.COL_FG2, "Settings for");
        ImGui.setCursorScreenPos(x, y + lineH + ui.fonts().body().getFontSize() * FIELD_GAP_EM);
        boolean isLocked = form.dirtyCount() > 0;
        pickerChoice.set(picker.selectedIndex());
        ImGui.beginDisabled(isLocked);
        boolean isPicked = ui.select("##settings-for", pickerChoice, picker.options(), w);
        ImGui.endDisabled();
        if (isPicked && !isLocked) {
            state.pickSettingsFor(picker.targetAt(pickerChoice.get()));
        }
        float noteY = ImGui.getCursorScreenPosY() - m.u(4) + m.u(1.5f);
        float noteH = renderNote(draw, x, noteY, w, isLocked ? PICKER_LOCKED_NOTE : picker.note(),
                isLocked ? ImGuiTheme.COL_WARN : ImGuiTheme.COL_FG2);
        ImGui.setCursorScreenPos(x, noteY);
        ImGui.dummy(w, noteH + m.u(2));
        draw.addLine(x, ImGui.getCursorScreenPosY() - m.u(2), x + w, ImGui.getCursorScreenPosY() - m.u(2),
                ImGuiTheme.COL_BORDER, m.hairline());
    }

    /** Wrapped caption text; returns its height. */
    private float renderNote(ImDrawList draw, float x, float y, float w, String text, int col) {
        ImFont font = ui.fonts().caption();
        float lineH = font.getFontSize() * LINE;
        float ty = y;
        for (String line : ui.wrap(font, text, w)) {
            ui.text(draw, font, x, ty, col, line);
            ty += lineH;
        }
        return ty - y;
    }

    /**
     * Under a field on a target: "from defaults" in grey while the value is the
     * inherited one, else "own value" and a link that puts the inherited value
     * back. Applying a value equal to the inherited one stores nothing, so the
     * link is how a target goes back to following.
     */
    private void renderSource(ConfigField field, ConfigEdits form, InheritedValue inherited) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY() - m.u(4) + m.u(1.5f);
        ImFont font = ui.fonts().caption();
        float h = font.getFontSize() * LINE;
        if (form.pending(field).equals(inherited.value())) {
            ui.textCentredY(draw, font, x, y, h, ImGuiTheme.COL_FG3, "from " + inherited.from());
        } else {
            String own = "own value";
            ui.textCentredY(draw, font, x, y, h, ImGuiTheme.COL_INFO, own);
            String link = "use " + inherited.from() + " (" + shown(field, inherited.value()) + ")";
            if (inlineLink("##use-inherited", link, font, x + ui.width(font, own) + m.u(2), y, h)) {
                form.set(field, inherited.value());
            }
        }
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(1f, h);
    }

    /** Link text: grey, the text colour and underlined while hovered. Returns true when clicked. */
    private boolean inlineLink(String id, String label, ImFont font, float x, float y, float h) {
        float wide = ui.width(font, label);
        ImGui.setCursorScreenPos(x, y);
        boolean clicked = ImGui.invisibleButton(id, wide, h);
        boolean isHovered = ImGui.isItemHovered();
        ImDrawList draw = ImGui.getWindowDrawList();
        int col = isHovered ? ImGuiTheme.COL_FG : ImGuiTheme.COL_FG2;
        ui.textCentredY(draw, font, x, y, h, col, label);
        float underline = y + (h + font.getFontSize()) * 0.5f;
        draw.addLine(x, underline, x + wide, underline, col, ui.m().hairline());
        return clicked;
    }

    /** A value as the link spells it: a toggle as on or off, long text cut short. */
    private static String shown(ConfigField field, String value) {
        return switch (field) {
            case BoolField ignored -> Boolean.parseBoolean(value) ? "on" : "off";
            case IntField ignored -> value;
            case ItemIdField ignored -> value;
            case ChoiceField ignored -> value;
            case StringField ignored -> value.length() <= LINK_VALUE_CHARS ? value
                    : value.substring(0, LINK_VALUE_CHARS) + "…";
        };
    }

    /** On a target, in place of "Restore defaults": put every inherited value back. */
    private void renderUseInherited(ConfigEdits form, SettingsFor picker) {
        boolean hasOwn = form.fields().stream().anyMatch(field -> picker.inheritedOf(field.key())
                .filter(inherited -> !form.pending(field).equals(inherited.value())).isPresent());
        if (ui.button("##inspector-inherit", Icons.ROTATE, "Clear own values", Tone.GHOST, hasOwn)) {
            for (ConfigField field : form.fields()) {
                picker.inheritedOf(field.key()).ifPresent(inherited -> form.set(field, inherited.value()));
            }
        }
    }

    private void renderLabel(ConfigField field, boolean dirty, float w) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImFont font = ui.fonts().small();
        float lineH = font.getFontSize() * LINE;
        float lx = x;
        if (dirty) {
            float r = m.dot() * 0.5f;
            draw.addCircleFilled(x + r, y + lineH * 0.5f, r, ImGuiTheme.COL_WARN);
            lx += m.dot() + m.u(1.5f);
        }
        ui.textCentredY(draw, font, lx, y, lineH, ImGuiTheme.COL_FG2, field.label());
        String type = typeName(field);
        ImFont mono = ui.fonts().monoCaption();
        ui.textCentredY(draw, mono, x + w - ui.width(mono, type), y, lineH, ImGuiTheme.COL_FG3, type);
        ImGui.dummy(w, lineH);
        ImGui.setCursorScreenPos(x, y + lineH + ui.fonts().body().getFontSize() * FIELD_GAP_EM);
    }

    private static String typeName(ConfigField field) {
        return switch (field) {
            case IntField ignored -> "int";
            case ItemIdField ignored -> "item id";
            case StringField ignored -> "text";
            case BoolField ignored -> "toggle";
            case ChoiceField ignored -> "choice";
        };
    }

    private void renderControl(ConfigField field, ConfigEdits form, InspectorTarget target, float w) {
        switch (field) {
            case IntField f -> ui.stepper("##v", form.intOf(f.key()), w);
            case ItemIdField f -> {
                ui.numberField("##v", form.intOf(f.key()), w);
                renderItemName(target.itemName().apply(form.intOf(f.key()).get()));
            }
            case StringField f -> ui.textField("##v", form.stringOf(f.key()), "", false, w);
            case BoolField f -> {
                if (ui.toggleRow("##v", f.label(), form.boolOf(f.key()).get(), w)) {
                    form.boolOf(f.key()).set(!form.boolOf(f.key()).get());
                }
            }
            case ChoiceField f -> renderChoice(f, form, w);
        }
    }

    private void renderChoice(ChoiceField f, ConfigEdits form, float w) {
        if (f.choices().size() <= SEGMENTED_MAX_CHOICES) {
            List<Segment> segments = f.choices().stream().map(Segment::of).toList();
            int clicked = ui.segmented("##v", segments, form.intOf(f.key()).get(), w, ui.m().controlHeight());
            if (clicked >= 0) {
                form.intOf(f.key()).set(clicked);
            }
        } else {
            ui.select("##v", form.intOf(f.key()), f.choices(), w);
        }
    }

    /**
     * The resolved name under an item id. The lookup is the host's in-process
     * cache reader; when it has no answer (unknown id, or no cache deployed) the
     * line says so neutrally rather than calling the id wrong.
     */
    private void renderItemName(Optional<String> name) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY() - m.u(4) + m.u(1.5f);
        ImFont font = ui.fonts().caption();
        float h = font.getFontSize() * LINE;
        int col = name.isPresent() ? ImGuiTheme.COL_ACCENT : ImGuiTheme.COL_FG3;
        String icon = name.isPresent() ? Icons.CHECK : Icons.QUESTION;
        ui.textCentredY(draw, font, x, y, h, col, icon);
        ui.textCentredY(draw, font, x + ui.width(font, icon) + m.u(1.5f), y, h, col,
                name.orElse("No name found for this id"));
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(1f, h);
    }

    private void renderScriptUi(InspectorTarget target) {
        ScriptUI scriptUi = target.customUi();
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float w = ImGui.getContentRegionAvailX();
        float h = ImGui.getContentRegionAvailY();
        String by = "Drawn by " + target.script().name()
                + (target.script().version().isBlank() ? "" : " " + target.script().version());
        ImFont cap = ui.fonts().caption();
        ui.text(draw, cap, x + m.u(3), y + m.u(3), ImGuiTheme.COL_FG3, Icons.PUZZLE);
        ui.text(draw, cap, x + m.u(3) + ui.width(cap, Icons.PUZZLE) + m.u(1.5f), y + m.u(3), ImGuiTheme.COL_FG3, by);
        dashedFrame(draw, x, y, w, h);
        float inset = m.u(3);
        float top = inset + cap.getFontSize() * LINE + m.u(3);
        ImGui.setCursorScreenPos(x + inset, y + top);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, ImGui.getStyle().getItemSpacingX(), m.u(1.5f));
        ImGui.beginChild("##script-ui", w - inset * 2f, Math.max(0f, h - top - inset), false);
        drawScriptUi(scriptUi, target.script().name());
        ImGui.endChild();
        ImGui.popStyleVar();
    }

    /** Script code: whatever it throws is shown in place, not propagated into the frame. */
    private static void drawScriptUi(ScriptUI scriptUi, String name) {
        try {
            scriptUi.render();
        } catch (RuntimeException e) {
            log.debug("Script UI for {} threw: {}", name, e.toString());
            ImGui.pushStyleColor(ImGuiCol.Text, ImGuiTheme.COL_DANGER);
            ImGui.textWrapped("This script's UI failed to draw: " + e.getMessage());
            ImGui.popStyleColor();
        }
    }

    private void dashedFrame(ImDrawList draw, float x, float y, float w, float h) {
        float dash = ui.fonts().body().getFontSize() * DASH_EM;
        int col = ImGuiTheme.COL_BORDER;
        float t = ui.m().hairline();
        for (float d = 0f; d < w; d += dash * 2f) {
            float e = Math.min(w, d + dash);
            draw.addLine(x + d, y + 0.5f, x + e, y + 0.5f, col, t);
            draw.addLine(x + d, y + h - 0.5f, x + e, y + h - 0.5f, col, t);
        }
        for (float d = 0f; d < h; d += dash * 2f) {
            float e = Math.min(h, d + dash);
            draw.addLine(x + 0.5f, y + d, x + 0.5f, y + e, col, t);
            draw.addLine(x + w - 0.5f, y + d, x + w - 0.5f, y + e, col, t);
        }
    }

    private void renderNothing(String message) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float cx = ImGui.getCursorScreenPosX() + ImGui.getContentRegionAvailX() * 0.5f;
        float y = ImGui.getCursorScreenPosY() + ui.fonts().body().getFontSize() * EMPTY_TOP_EM;
        ImFont iconFont = ui.fonts().titleMedium();
        ui.text(draw, iconFont, cx - ui.width(iconFont, Icons.SLIDERS) * 0.5f, y, ImGuiTheme.COL_FG3, Icons.SLIDERS);
        ImFont font = ui.fonts().small();
        float ty = y + iconFont.getFontSize() + ui.m().u(2.5f);
        ui.text(draw, font, cx - ui.width(font, message) * 0.5f, ty, ImGuiTheme.COL_FG2, message);
    }

    // ── Footer ─────────────────────────────────────────────────────────────

    private float footerHeight() {
        return ui.m().u(3) * 2f + ui.m().controlHeight();
    }

    private void renderFooter(ConfigEdits form, InspectorTarget target, float x, float y, float width) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addLine(x, y + 0.5f, x + width, y + 0.5f, ImGuiTheme.COL_BORDER, m.hairline());
        int dirty = form.dirtyCount();
        float rowY = y + m.u(3);
        float rowH = m.controlHeight();
        renderSaveState(draw, dirty, x + m.u(4), rowY, rowH);
        float applyW = ui.buttonWidth(null, "Apply", Tone.PRIMARY);
        float resetW = ui.buttonWidth(null, "Reset", Tone.GHOST);
        float bx = x + width - m.u(4) - applyW - m.u(2) - resetW;
        ImGui.setCursorScreenPos(bx, rowY);
        if (ui.button("##inspector-reset", null, "Reset", Tone.GHOST, dirty > 0)) {
            form.revert();
        }
        ImGui.setCursorScreenPos(bx + resetW + m.u(2), rowY);
        if (ui.button("##inspector-apply", null, "Apply", Tone.PRIMARY, dirty > 0)) {
            target.apply().accept(form.toConfig());
            form.markApplied();
        }
    }

    private void renderSaveState(ImDrawList draw, int dirty, float x, float y, float h) {
        ImGuiTheme.Metrics m = ui.m();
        ImFont font = ui.fonts().caption();
        if (dirty > 0) {
            float r = m.dot() * 0.5f;
            draw.addCircleFilled(x + r, y + h * 0.5f, r, ImGuiTheme.COL_WARN);
            String text = dirty + " unsaved change" + (dirty == 1 ? "" : "s");
            ui.textCentredY(draw, font, x + m.dot() + m.u(1.5f), y, h, ImGuiTheme.COL_WARN, text);
            return;
        }
        ui.textCentredY(draw, font, x, y, h, ImGuiTheme.COL_ACCENT, Icons.CHECK);
        ui.textCentredY(draw, font, x + ui.width(font, Icons.CHECK) + m.u(1.5f), y, h, ImGuiTheme.COL_FG2,
                "Up to date");
    }
}
