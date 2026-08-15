package com.botwithus.bot.cli.gui;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ConfigField.BoolField;
import com.botwithus.bot.api.config.ConfigField.ChoiceField;
import com.botwithus.bot.api.config.ConfigField.IntField;
import com.botwithus.bot.api.config.ConfigField.ItemIdField;
import com.botwithus.bot.api.config.ConfigField.StringField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.core.runtime.ScriptRunner;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Floating editor for a script's {@link ConfigField} declarations.
 *
 * <p>Three regions, top to bottom:
 * <ol>
 *   <li>A custom-drawn banner with the script's category icon, name, and subtitle.</li>
 *   <li>A scrollable body with one field per row (label caption + control).</li>
 *   <li>A pinned action bar with a dirty indicator and Apply / Reset / Close.</li>
 * </ol>
 *
 * <p>The window default-sizes once via {@link ImGuiCond#FirstUseEver} and is freely
 * resizable; an explicit min size constraint keeps the layout from collapsing.
 */
public class ScriptConfigPanel {

    public ScriptConfigPanel() {}

    private static final int STRING_BUFFER_SIZE = 256;

    private static final float DEFAULT_WIDTH_EM = 26f;
    private static final float DEFAULT_HEIGHT_EM = 32f;
    private static final float MIN_WIDTH_EM = 20f;
    private static final float MIN_HEIGHT_EM = 18f;

    private ScriptRunner runner;
    private List<ConfigField> fields;
    private final EditState edit = new EditState();
    private final ImBoolean open = new ImBoolean(false);

    public void open(ScriptRunner runner) {
        this.runner = runner;
        this.fields = runner.getConfigFields();
        if (fields == null || fields.isEmpty()) {
            this.fields = List.of();
        }

        ScriptConfig current = runner.getCurrentConfig();
        edit.clear();
        for (ConfigField field : fields) {
            edit.seed(field, current);
        }
        open.set(true);
    }

    public boolean isOpen() {
        return open.get();
    }

    public void close() {
        open.set(false);
    }

    public void render() {
        if (!open.get() || runner == null) {
            return;
        }
        if (runner.isDisposed()) {
            open.set(false);
            runner = null;
            return;
        }

        float fontH = ImGui.getFontSize();
        ImGui.setNextWindowSize(fontH * DEFAULT_WIDTH_EM, fontH * DEFAULT_HEIGHT_EM,
                ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSizeConstraints(
                fontH * MIN_WIDTH_EM, fontH * MIN_HEIGHT_EM,
                Float.MAX_VALUE, Float.MAX_VALUE);

        ScriptManifest manifest = runner.getManifest();
        CategoryStyle.Style cs = CategoryStyle.of(
                manifest != null ? manifest.category() : ScriptCategory.UNCATEGORIZED);

        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        boolean visible = ImGui.begin(
                runner.getScriptName() + "###scriptConfigPanel-" + runner.getScriptName(),
                open, ImGuiWindowFlags.NoCollapse);
        ImGui.popStyleVar();

        if (visible) {
            renderBanner(manifest, cs);
            renderBodyAndActionBar(cs);
        }
        ImGui.end();
    }

    // ── Banner ────────────────────────────────────────────────────────────

    private void renderBanner(ScriptManifest manifest, CategoryStyle.Style cs) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float fontH = ImGui.getFontSize();

        // Height is row-count driven. Two rows (title + subtitle) end at 0.85 + 1.45 + 1.0
        // em, so 4.2 leaves the same 0.85 em below them as above the title. A description
        // adds a row at +2.75 em whose glyphs run to 4.6 — under the old 4.2 the accent
        // stripe and its hairline crossed the text and the rest spilled out of the banner.
        float bannerH = fontH * (hasDescription(manifest) ? 5.2f : 4.2f);
        float padX = fontH * 1.1f;
        float padY = fontH * 0.85f;

        float x0 = ImGui.getCursorScreenPosX();
        float y0 = ImGui.getCursorScreenPosY();
        float availW = ImGui.getContentRegionAvailX();
        float x1 = x0 + availW;
        float y1 = y0 + bannerH;

        drawBannerBackground(draw, cs, x0, y0, x1, y1, fontH);

        float iconSize = fontH * 2.4f;
        float iconX = x0 + padX;
        float iconY = y0 + (bannerH - iconSize) * 0.5f;
        drawCategoryChip(draw, cs, iconX, iconY, iconSize, fontH);

        float textX = iconX + iconSize + fontH * 0.85f;
        float titleY = y0 + padY;
        drawTitleStack(draw, manifest, fontH, textX, titleY, x1 - textX - padX);

        ImGui.dummy(availW, bannerH);
    }

    /** Gradient surface→input-bg backdrop plus a soft accent wash on the right, then the accent stripe + hairline. */
    private static void drawBannerBackground(ImDrawList draw, CategoryStyle.Style cs,
                                             float x0, float y0, float x1, float y1, float fontH) {
        int bgLeft = ImGuiTheme.imCol32(
                ImGuiTheme.SURFACE_R, ImGuiTheme.SURFACE_G, ImGuiTheme.SURFACE_B, 1f);
        int bgRight = ImGuiTheme.imCol32(
                ImGuiTheme.INPUT_BG_R, ImGuiTheme.INPUT_BG_G, ImGuiTheme.INPUT_BG_B, 1f);
        draw.addRectFilledMultiColor(x0, y0, x1, y1, bgLeft, bgRight, bgRight, bgLeft);

        int accentSoft = ImGuiTheme.imCol32(cs.r(), cs.g(), cs.b(), 0f);
        int accentWash = ImGuiTheme.imCol32(cs.r(), cs.g(), cs.b(), 0.14f);
        draw.addRectFilledMultiColor(x0, y0, x1, y1,
                accentSoft, accentWash, accentWash, accentSoft);

        float stripeH = Math.max(2f, fontH * 0.12f);
        int stripeCol = ImGuiTheme.imCol32(cs.r(), cs.g(), cs.b(), 0.85f);
        draw.addRectFilled(x0, y1 - stripeH, x1, y1, stripeCol);

        int hairline = ImGuiTheme.imCol32(
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.35f);
        draw.addLine(x0, y1 - stripeH - 1f, x1, y1 - stripeH - 1f, hairline, 1f);
    }

    /** Rounded colored chip with the category icon centred inside. */
    private static void drawCategoryChip(ImDrawList draw, CategoryStyle.Style cs,
                                         float iconX, float iconY, float iconSize, float fontH) {
        float iconRounding = fontH * 0.45f;
        int chipBg = ImGuiTheme.imCol32(cs.r(), cs.g(), cs.b(), 0.16f);
        int chipBorder = ImGuiTheme.imCol32(cs.r(), cs.g(), cs.b(), 0.45f);
        draw.addRectFilled(iconX, iconY, iconX + iconSize, iconY + iconSize, chipBg, iconRounding);
        draw.addRect(iconX, iconY, iconX + iconSize, iconY + iconSize, chipBorder, iconRounding);

        ImVec2 iconTextSize = new ImVec2();
        ImGui.calcTextSize(iconTextSize, cs.icon());
        int iconCol = ImGuiTheme.imCol32(cs.r(), cs.g(), cs.b(), 0.95f);
        draw.addText(
                iconX + (iconSize - iconTextSize.x) * 0.5f,
                iconY + (iconSize - iconTextSize.y) * 0.5f,
                iconCol, cs.icon());
    }

    /** Title, subtitle ("Script Settings · v1.0 · by Author"), and truncated description. */
    private void drawTitleStack(ImDrawList draw, ScriptManifest manifest,
                                float fontH, float textX, float titleY, float maxTextWidth) {
        int titleCol = ImGuiTheme.imCol32(
                ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 1f);
        draw.addText(textX, titleY, titleCol, runner.getScriptName());

        StringBuilder subtitle = new StringBuilder(Icons.SLIDERS + "  Script Settings");
        if (manifest != null && !manifest.version().isEmpty()) {
            subtitle.append("  ·  v").append(manifest.version());
        }
        if (manifest != null && !manifest.author().isEmpty()) {
            subtitle.append("  ·  by ").append(manifest.author());
        }
        int subtitleCol = ImGuiTheme.imCol32(
                ImGuiTheme.TEXT_SEC_R, ImGuiTheme.TEXT_SEC_G, ImGuiTheme.TEXT_SEC_B, 0.92f);
        draw.addText(textX, titleY + fontH * 1.45f, subtitleCol, subtitle.toString());

        if (hasDescription(manifest)) {
            int descCol = ImGuiTheme.imCol32(
                    ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 0.9f);
            String desc = truncateToWidth(manifest.description(), maxTextWidth);
            draw.addText(textX, titleY + fontH * 2.75f, descCol, desc);
        }
    }

    /** Whether the banner gets a third text row — keeps its height and its content in step. */
    private static boolean hasDescription(ScriptManifest manifest) {
        return manifest != null && !manifest.description().isEmpty();
    }

    private static String truncateToWidth(String text, float maxWidth) {
        ImVec2 s = new ImVec2();
        ImGui.calcTextSize(s, text);
        if (s.x <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            ImGui.calcTextSize(s, text.substring(0, mid) + ellipsis);
            if (s.x <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo) + ellipsis;
    }

    // ── Body + action bar ─────────────────────────────────────────────────

    private void renderBodyAndActionBar(CategoryStyle.Style cs) {
        float fontH = ImGui.getFontSize();
        float actionBarH = ImGui.getFrameHeight() + fontH * 1.4f;
        float bodyPadX = fontH * 1.1f;
        float bodyPadY = fontH * 0.9f;

        // Body child fills the remaining space above the pinned action bar.
        float bodyH = ImGui.getContentRegionAvailY() - actionBarH;
        if (bodyH < fontH * 4f) {
            bodyH = fontH * 4f;
        }

        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, bodyPadX, bodyPadY);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing,
                ImGui.getStyle().getItemSpacingX(), fontH * 0.55f);
        ImGui.pushStyleColor(ImGuiCol.ChildBg,
                ImGuiTheme.BG_R, ImGuiTheme.BG_G, ImGuiTheme.BG_B, 1f);

        ImGui.beginChild("##configBody", 0, bodyH, false);

        if (scriptOwnsBody()) {
            renderCustomUi();
        } else if (fields == null || fields.isEmpty()) {
            renderEmptyState();
        } else {
            renderFields(cs);
        }

        ImGui.endChild();
        ImGui.popStyleColor();
        ImGui.popStyleVar(2);

        renderActionBar();
    }

    /**
     * A script that ships its own {@link ScriptUI} owns the entire body: we render
     * that UI and nothing else.
     *
     * <p>Stacking the generated {@link ConfigField} rows above it — which is what this
     * panel used to do — is wrong on two counts. The rows are the same values the
     * script's UI already presents, so every control appeared twice; and a UI that
     * paints a full-window backdrop (any {@code BwuScriptUI}) fills
     * {@code getWindowPos()}‥{@code getWindowSize()} on the window draw list, which in
     * submission order lands on top of every row drawn before it in this same child.
     * The rows kept their layout space but were painted over, so the panel opened onto
     * a tall band of dead space before the script's UI came into view.</p>
     */
    private boolean scriptOwnsBody() {
        return runner != null && runner.getScript() != null && runner.getScript().getUI() != null;
    }

    private void renderCustomUi() {
        ScriptUI ui = runner.getScript().getUI();
        if (ui == null) {
            return;
        }
        try {
            ui.render();
        } catch (Exception e) {
            ImGui.textColored(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 1f,
                    "UI error: " + e.getMessage());
        }
    }

    private void renderEmptyState() {
        float fontH = ImGui.getFontSize();
        float availW = ImGui.getContentRegionAvailX();
        float availH = ImGui.getContentRegionAvailY();

        ImGui.dummy(0f, Math.max(0f, (availH - fontH * 5f) * 0.4f));

        String icon = Icons.SLIDERS;
        ImVec2 iconSize = new ImVec2();
        ImGui.calcTextSize(iconSize, icon);
        ImGui.setCursorPosX(ImGui.getCursorPosX() + (availW - iconSize.x) * 0.5f);
        ImGui.textColored(
                ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 0.7f, icon);

        ImGui.dummy(0f, fontH * 0.4f);

        String title = "No configurable settings";
        ImVec2 titleSize = new ImVec2();
        ImGui.calcTextSize(titleSize, title);
        ImGui.setCursorPosX(ImGui.getCursorPosX() + (availW - titleSize.x) * 0.5f);
        ImGui.text(title);

        String subtitle = "This script doesn't expose any ConfigField entries.";
        ImVec2 subtitleSize = new ImVec2();
        ImGui.calcTextSize(subtitleSize, subtitle);
        ImGui.setCursorPosX(ImGui.getCursorPosX() + (availW - subtitleSize.x) * 0.5f);
        GuiHelpers.textMuted(subtitle);
    }

    private void renderFields(CategoryStyle.Style cs) {
        GuiHelpers.sectionHeader("Settings");
        ImGui.dummy(0f, ImGui.getFontSize() * 0.2f);

        for (int i = 0; i < fields.size(); i++) {
            ConfigField field = fields.get(i);
            ImGui.pushID(field.key());
            renderFieldRow(field, cs);
            ImGui.popID();

            if (i < fields.size() - 1) {
                ImGui.dummy(0f, ImGui.getFontSize() * 0.25f);
            }
        }
    }

    private void renderFieldRow(ConfigField field, CategoryStyle.Style cs) {
        float fontH = ImGui.getFontSize();

        // Caption row: label on the left, optional type hint chip on the right.
        ImGui.textColored(
                ImGuiTheme.TEXT_SEC_R, ImGuiTheme.TEXT_SEC_G, ImGuiTheme.TEXT_SEC_B, 0.92f,
                field.label());

        String hint = typeHint(field);
        if (hint != null) {
            float availW = ImGui.getContentRegionAvailX();
            ImVec2 hintSize = new ImVec2();
            ImGui.calcTextSize(hintSize, hint);
            float chipW = hintSize.x + fontH * 0.6f;
            if (availW > chipW + fontH * 0.4f) {
                ImGui.sameLine();
                ImGui.setCursorPosX(ImGui.getCursorPosX() + availW - chipW);
                drawTypeChip(hint);
            }
        }

        // Control row: the input occupies the full body width.
        ImGui.pushItemWidth(-1f);
        switch (field) {
            case IntField f -> ImGui.inputInt("##" + f.key(), edit.ints.get(f.key()));
            case ItemIdField f -> ImGui.inputInt("##" + f.key(), edit.ints.get(f.key()));
            case StringField f -> ImGui.inputText("##" + f.key(), edit.strings.get(f.key()));
            case BoolField f -> renderBoolRow(f, cs);
            case ChoiceField f -> ImGui.combo("##" + f.key(), edit.ints.get(f.key()),
                    f.choices().toArray(new String[0]));
        }
        ImGui.popItemWidth();
    }

    private void renderBoolRow(BoolField f, CategoryStyle.Style cs) {
        ImBoolean state = edit.bools.get(f.key());

        // Custom tactile toggle aligned to the right of an elevated row.
        float fontH = ImGui.getFontSize();
        float padX = fontH * 0.6f;
        float padY = fontH * 0.25f;
        float rowH = ImGui.getFrameHeight();

        float x0 = ImGui.getCursorScreenPosX();
        float y0 = ImGui.getCursorScreenPosY();
        float availW = ImGui.getContentRegionAvailX();

        ImDrawList draw = ImGui.getWindowDrawList();
        int rowBg = ImGuiTheme.imCol32(
                ImGuiTheme.INPUT_BG_R, ImGuiTheme.INPUT_BG_G, ImGuiTheme.INPUT_BG_B, 1f);
        int rowBorder = ImGuiTheme.imCol32(
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.5f);
        float rounding = ImGui.getStyle().getFrameRounding();
        draw.addRectFilled(x0, y0, x0 + availW, y0 + rowH, rowBg, rounding);
        draw.addRect(x0, y0, x0 + availW, y0 + rowH, rowBorder, rounding);

        // State caption (left)
        int captionCol = ImGuiTheme.imCol32(
                state.get() ? cs.r() : ImGuiTheme.TEXT_SEC_R,
                state.get() ? cs.g() : ImGuiTheme.TEXT_SEC_G,
                state.get() ? cs.b() : ImGuiTheme.TEXT_SEC_B,
                state.get() ? 1f : 0.85f);
        String caption = state.get() ? "Enabled" : "Disabled";
        draw.addText(x0 + padX, y0 + (rowH - fontH) * 0.5f, captionCol, caption);

        // Toggle (right)
        ImVec2 sw = new ImVec2();
        ImGui.calcTextSize(sw, " ");
        float toggleW = fontH * 1.05f * 1.9f;
        ImGui.setCursorScreenPos(x0 + availW - toggleW - padX, y0 + (rowH - fontH * 1.05f) * 0.5f);
        if (GuiHelpers.toggleSwitch("##tg-" + f.key(), state.get())) {
            state.set(!state.get());
        }

        // Reserve the row's vertical space — the toggle and rect are absolute-positioned,
        // so without this the layout cursor wouldn't advance.
        ImGui.setCursorScreenPos(x0, y0);
        ImGui.dummy(availW, rowH);
    }

    private void drawTypeChip(String hint) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float fontH = ImGui.getFontSize();
        float padX = fontH * 0.35f;
        float padY = fontH * 0.05f;
        float rounding = fontH * 0.2f;

        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY() + fontH * 0.05f;
        ImVec2 s = new ImVec2();
        ImGui.calcTextSize(s, hint);

        int bg = ImGuiTheme.imCol32(
                ImGuiTheme.ELEVATED_R, ImGuiTheme.ELEVATED_G, ImGuiTheme.ELEVATED_B, 0.55f);
        int border = ImGuiTheme.imCol32(
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.4f);
        int text = ImGuiTheme.imCol32(
                ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 0.95f);

        draw.addRectFilled(x, y, x + s.x + padX * 2, y + s.y + padY * 2, bg, rounding);
        draw.addRect(x, y, x + s.x + padX * 2, y + s.y + padY * 2, border, rounding);
        draw.addText(x + padX, y + padY, text, hint);

        ImGui.dummy(s.x + padX * 2, s.y + padY * 2);
    }

    private static String typeHint(ConfigField field) {
        return switch (field) {
            case IntField ignored -> "int";
            case ItemIdField ignored -> "item id";
            case StringField ignored -> "text";
            case BoolField ignored -> null;
            case ChoiceField f -> f.choices().size() + " choices";
        };
    }

    // ── Action bar ────────────────────────────────────────────────────────

    private void renderActionBar() {
        float fontH = ImGui.getFontSize();
        float padX = fontH * 1.1f;
        float padY = fontH * 0.55f;
        float barH = ImGui.getFrameHeight() + padY * 2;

        float x0 = ImGui.getCursorScreenPosX();
        float y0 = ImGui.getCursorScreenPosY();
        float availW = ImGui.getContentRegionAvailX();

        ImDrawList draw = ImGui.getWindowDrawList();
        int bg = ImGuiTheme.imCol32(
                ImGuiTheme.SURFACE_R, ImGuiTheme.SURFACE_G, ImGuiTheme.SURFACE_B, 1f);
        int topBorder = ImGuiTheme.imCol32(
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.5f);
        draw.addRectFilled(x0, y0, x0 + availW, y0 + barH, bg);
        draw.addLine(x0, y0, x0 + availW, y0, topBorder, 1f);

        ImGui.setCursorScreenPos(x0 + padX, y0 + padY);

        boolean ownsBody = scriptOwnsBody();
        boolean dirty = !ownsBody && isDirty();
        renderDirtyIndicator(dirty);

        // Right-aligned action buttons: Reset (secondary) · Close (secondary) · Apply (primary).
        float closeW = textButtonWidth("Close");
        float resetW = textButtonWidth(Icons.ROTATE + "  Reset");
        float applyW = textButtonWidth(Icons.CHECK + "  Apply");
        float gap = ImGui.getStyle().getItemSpacingX();
        float buttonsW = ownsBody ? closeW : closeW + resetW + applyW + gap * 2;

        ImGui.sameLine();
        float rightEdge = availW - padX;
        ImGui.setCursorPosX(rightEdge - buttonsW);

        // When the script owns the body, Reset/Apply would act on generated fields that
        // are not on screen: Apply would push the snapshot taken when this panel opened
        // (clobbering anything the script's UI has changed since) and Reset would drop
        // the whole config back to defaults. The script's UI persists on its own, so
        // only Close is offered.
        if (!ownsBody) {
            if (GuiHelpers.buttonSecondary(Icons.ROTATE + "  Reset##cfgReset", resetW, ImGui.getFrameHeight())) {
                resetToDefaults();
            }
            ImGui.sameLine(0, gap);
        }
        if (GuiHelpers.buttonSecondary("Close##cfgClose", closeW, ImGui.getFrameHeight())) {
            open.set(false);
        }
        if (ownsBody) {
            ImGui.setCursorScreenPos(x0, y0 + barH);
            ImGui.dummy(availW, 0);
            return;
        }
        ImGui.sameLine(0, gap);

        if (dirty) {
            if (GuiHelpers.buttonPrimary(Icons.CHECK + "  Apply##cfgApply", applyW, ImGui.getFrameHeight())) {
                applyConfig();
            }
        } else {
            // Disabled-feel Apply when nothing to save — still clickable to no-op cheaply.
            ImGui.pushStyleVar(ImGuiStyleVar.Alpha, 0.45f);
            if (GuiHelpers.buttonSecondary(Icons.CHECK + "  Apply##cfgApply", applyW, ImGui.getFrameHeight())) {
                applyConfig();
            }
            ImGui.popStyleVar();
        }

        // Advance past the bar.
        ImGui.setCursorScreenPos(x0, y0 + barH);
        ImGui.dummy(availW, 0);
    }

    private void renderDirtyIndicator(boolean dirty) {
        float fontH = ImGui.getFontSize();
        ImDrawList draw = ImGui.getWindowDrawList();

        if (dirty) {
            float r = fontH * 0.22f;
            float dotX = ImGui.getCursorScreenPosX() + r;
            float dotY = ImGui.getCursorScreenPosY() + ImGui.getFrameHeight() * 0.5f;
            int glow = ImGuiTheme.imCol32(
                    ImGuiTheme.YELLOW_R, ImGuiTheme.YELLOW_G, ImGuiTheme.YELLOW_B, 0.35f);
            int core = ImGuiTheme.imCol32(
                    ImGuiTheme.YELLOW_R, ImGuiTheme.YELLOW_G, ImGuiTheme.YELLOW_B, 1f);
            draw.addCircleFilled(dotX, dotY, r * 1.7f, glow);
            draw.addCircleFilled(dotX, dotY, r, core);

            ImGui.dummy(r * 2.4f, ImGui.getFrameHeight());
            ImGui.sameLine(0, fontH * 0.4f);
            ImGui.textColored(
                    ImGuiTheme.YELLOW_R, ImGuiTheme.YELLOW_G, ImGuiTheme.YELLOW_B, 1f,
                    "Unsaved changes");
        } else {
            // Match the dirty-line height so the action buttons sit on the same baseline.
            float r = fontH * 0.22f;
            float dotX = ImGui.getCursorScreenPosX() + r;
            float dotY = ImGui.getCursorScreenPosY() + ImGui.getFrameHeight() * 0.5f;
            int idle = ImGuiTheme.imCol32(
                    ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 0.45f);
            draw.addCircleFilled(dotX, dotY, r * 0.6f, idle);

            ImGui.dummy(r * 2.4f, ImGui.getFrameHeight());
            ImGui.sameLine(0, fontH * 0.4f);
            GuiHelpers.textMuted("Up to date");
        }
    }

    private static float textButtonWidth(String label) {
        ImVec2 s = new ImVec2();
        ImGui.calcTextSize(s, label);
        return s.x + ImGui.getStyle().getFramePaddingX() * 2 + ImGui.getFontSize() * 0.4f;
    }

    // ── State + actions ───────────────────────────────────────────────────

    private boolean isDirty() {
        if (fields == null || fields.isEmpty()) {
            return false;
        }
        ScriptConfig current = runner.getCurrentConfig();
        Map<String, String> applied = current != null ? current.asMap() : Map.of();
        for (ConfigField field : fields) {
            String pending = edit.stringify(field);
            String existing = applied.getOrDefault(field.key(), field.defaultAsString());
            if (!Objects.equals(pending, existing)) {
                return true;
            }
        }
        return false;
    }

    private void applyConfig() {
        Map<String, String> values = new LinkedHashMap<>();
        for (ConfigField field : fields) {
            edit.collect(field, values);
        }
        runner.applyConfig(new ScriptConfig(values));
    }

    private void resetToDefaults() {
        for (ConfigField field : fields) {
            edit.reset(field);
        }
    }

    /**
     * Typed editor state for the open form. Each field variant stores its mutable
     * ImGui wrapper in the matching typed map, so dispatch never needs a cast.
     */
    private static final class EditState {

        private final Map<String, ImInt> ints = new LinkedHashMap<>();
        private final Map<String, ImString> strings = new LinkedHashMap<>();
        private final Map<String, ImBoolean> bools = new LinkedHashMap<>();

        void clear() {
            ints.clear();
            strings.clear();
            bools.clear();
        }

        void seed(ConfigField field, ScriptConfig current) {
            switch (field) {
                case IntField f -> ints.put(f.key(), new ImInt(intOr(current, f.key(), f.value())));
                case ItemIdField f -> ints.put(f.key(), new ImInt(intOr(current, f.key(), f.value())));
                case BoolField f -> bools.put(f.key(), new ImBoolean(boolOr(current, f.key(), f.value())));
                case StringField f -> {
                    String val = stringOr(current, f.key(), f.value());
                    strings.put(f.key(), new ImString(val != null ? val : "", STRING_BUFFER_SIZE));
                }
                case ChoiceField f -> {
                    String val = stringOr(current, f.key(), f.value());
                    ints.put(f.key(), new ImInt(Math.max(f.choices().indexOf(val), 0)));
                }
            }
        }

        void collect(ConfigField field, Map<String, String> values) {
            switch (field) {
                case IntField f -> values.put(f.key(), String.valueOf(ints.get(f.key()).get()));
                case ItemIdField f -> values.put(f.key(), String.valueOf(ints.get(f.key()).get()));
                case StringField f -> values.put(f.key(), strings.get(f.key()).get());
                case BoolField f -> values.put(f.key(), String.valueOf(bools.get(f.key()).get()));
                case ChoiceField f -> {
                    int idx = ints.get(f.key()).get();
                    if (idx >= 0 && idx < f.choices().size()) {
                        values.put(f.key(), f.choices().get(idx));
                    }
                }
            }
        }

        String stringify(ConfigField field) {
            return switch (field) {
                case IntField f -> String.valueOf(ints.get(f.key()).get());
                case ItemIdField f -> String.valueOf(ints.get(f.key()).get());
                case StringField f -> strings.get(f.key()).get();
                case BoolField f -> String.valueOf(bools.get(f.key()).get());
                case ChoiceField f -> {
                    int idx = ints.get(f.key()).get();
                    yield (idx >= 0 && idx < f.choices().size()) ? f.choices().get(idx) : f.value();
                }
            };
        }

        void reset(ConfigField field) {
            switch (field) {
                case IntField f -> ints.get(f.key()).set(f.value());
                case ItemIdField f -> ints.get(f.key()).set(f.value());
                case StringField f -> strings.get(f.key()).set(f.value());
                case BoolField f -> bools.get(f.key()).set(f.value());
                case ChoiceField f -> ints.get(f.key()).set(Math.max(f.choices().indexOf(f.value()), 0));
            }
        }

        private static int intOr(ScriptConfig current, String key, int defaultValue) {
            return current != null ? current.getInt(key, defaultValue) : defaultValue;
        }

        private static boolean boolOr(ScriptConfig current, String key, boolean defaultValue) {
            return current != null ? current.getBoolean(key, defaultValue) : defaultValue;
        }

        private static String stringOr(ScriptConfig current, String key, String defaultValue) {
            return current != null ? current.getString(key, defaultValue) : defaultValue;
        }
    }
}
