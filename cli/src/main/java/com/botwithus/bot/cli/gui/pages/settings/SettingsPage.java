package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.nav.NavBadge;
import com.botwithus.bot.cli.gui.nav.Page;
import com.botwithus.bot.cli.gui.nav.PageId;
import com.botwithus.bot.core.alerts.AlertService;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Settings: a section list on the left and one scrolling column on the right.
 * Every change saves as it is made — the header says "saving …" then
 * "saved" — so there is no Apply button. "Find a setting" (Ctrl+F) hides the
 * rows that do not match and dims the sections left empty.
 */
public final class SettingsPage implements Page {

    private static final float LINE_HEIGHT = 1.45f;
    private static final float TOC_WIDTH_EM = 13.33f;
    private static final float COLUMN_MAX_EM = 50.67f;
    private static final float SEARCH_WIDTH_EM = 16f;
    private static final float SECTION_ICON_EM = 1.6f;
    private static final float BOTTOM_PAD_EM = 5.33f;
    private static final float SPY_OFFSET_EM = 4f;
    private static final float DIM_ALPHA = 0.4f;
    private static final float TOC_GAP_PX = 2f;
    private static final int QUERY_BYTES = 128;
    private static final String FAILED_BADGE = "!";
    private static final String DOT = " · ";

    private final Controls ui;
    private final SettingsModel model;
    private final SettingsRows rows;
    private final SecretFields secrets = new SecretFields();
    private final ImString query = new ImString(QUERY_BYTES);
    private final Map<SettingsSection, Float> sectionTops = new EnumMap<>(SettingsSection.class);
    private SettingsSection current = SettingsSection.CONNECTING;
    private Optional<SettingsSection> scrollTo = Optional.empty();
    /** Set by the dev preview only: how far to scroll the column down on the next frame. */
    private float scrollDownBy;

    public SettingsPage(Controls ui, SettingsModel model) {
        this.ui = ui;
        this.model = model;
        RowEdits edits = new RowEdits();
        SettingsWidgets widgets = new SettingsWidgets(ui);
        this.rows = new SettingsRows(ui, widgets, edits, new SettingsTables(ui, widgets, edits),
                new IntegrationRows(ui, widgets, edits, secrets));
    }

    @Override
    public PageId id() {
        return PageId.SETTINGS;
    }

    /** A warning mark in the sidebar while the settings file could not be written. */
    @Override
    public Optional<NavBadge> badge() {
        return model.save().state() == SaveLine.State.FAILED
                ? Optional.of(new NavBadge(FAILED_BADGE, true))
                : Optional.empty();
    }

    /** Types {@code text} into "Find a setting", as the user would. For the dev preview. */
    void find(String text) {
        query.set(text);
    }

    /** Scrolls to {@code section}, as clicking it in the section list does. For the dev preview. */
    void showSection(SettingsSection section) {
        scrollTo = Optional.of(section);
    }

    /** Types {@code text} into the named setting's box and presses Enter. For the dev preview. */
    void type(String name, String text) {
        rows.commit(name, text, model);
    }

    /** Types {@code text} into the service's secret box and presses Enter. For the dev preview. */
    void typeSecret(AlertService service, String text) {
        secrets.buffer(service).set(text);
        secrets.commit(service, model);
    }

    /** Scrolls the column down by {@code px}, as the mouse wheel would. For the dev preview. */
    void scrollDown(float px) {
        scrollDownBy += px;
    }

    /** Presses the service's Show button. For the dev preview. */
    void revealSecret(AlertService service) {
        secrets.toggleReveal(service, model);
    }

    @Override
    public void render() {
        SettingsView view = model.view();
        List<SectionView> shown = SettingsFind.filter(view.sections(), query.get());
        float headerH = header(view);
        float bodyH = ImGui.getWindowHeight() - headerH;
        ImGui.setCursorPos(0f, headerH);
        toc(view.sections(), shown, bodyH);
        ImGui.sameLine(0f, 0f);
        content(shown, bodyH);
    }

    // ── Header ─────────────────────────────────────────────────────────────

    /** Title, save line, what the last button did, the find box and Open file; returns its height. */
    private float header(SettingsView view) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float x0 = ImGui.getWindowPosX();
        float y0 = ImGui.getWindowPosY();
        float w = ImGui.getWindowWidth();
        float ctlH = m.controlHeight();
        float h = m.u(4) + ctlH + m.u(3);
        float bandY = y0 + m.u(4);
        ImFont title = ui.fonts().titleMedium();
        ui.textCentredY(draw, title, x0 + m.u(5), bandY, ctlH, ImGuiTheme.COL_FG, "Settings");
        float metaX = x0 + m.u(5) + ui.width(title, "Settings") + m.u(3);
        float right = headerControls(x0 + w - m.u(5), bandY, ctlH);
        float metaEnd = saveLine(draw, view, metaX, bandY, ctlH, right - m.u(4));
        view.note().ifPresent(note -> note(draw, note, metaEnd + m.u(4), bandY, ctlH, right - m.u(4)));
        draw.addLine(x0, y0 + h - m.hairline(), x0 + w, y0 + h - m.hairline(), ImGuiTheme.COL_BORDER, m.hairline());
        return h;
    }

    /** The find box and Open file, right-aligned to {@code right}; returns their left edge. */
    private float headerControls(float right, float y, float h) {
        ImGuiTheme.Metrics m = ui.m();
        float openW = ui.buttonWidth(Icons.FILE_LINES, "Open file", Tone.GHOST);
        ImGui.setCursorScreenPos(right - openW, y);
        if (ui.button("##open-config", Icons.FILE_LINES, "Open file", Tone.GHOST, true, h)) {
            model.open(SettingsPlace.CONFIG_FILE);
        }
        float searchW = m.fontSize() * SEARCH_WIDTH_EM;
        float searchX = right - openW - m.u(3) - searchW;
        ImGui.setCursorScreenPos(searchX, y);
        boolean isFindKey = ImGui.getIO().getKeyCtrl() && ImGui.isKeyPressed(ImGuiKey.F, false);
        ui.searchBox("##find-setting", query, "Find a setting", searchW, h, isFindKey);
        return searchX;
    }

    /**
     * "✓ saved · ~/.botwithus/config.properties", coloured by state, kept left of
     * {@code right}: the file is dropped first, then the status text is shortened.
     * Returns where it ends.
     */
    private float saveLine(ImDrawList draw, SettingsView view, float x, float y, float h, float right) {
        ImFont mono = ui.fonts().monoCaption();
        ImFont caption = ui.fonts().caption();
        SaveLine save = view.save();
        int col = switch (save.state()) {
            case SAVED -> ImGuiTheme.COL_ACCENT;
            case SAVING -> ImGuiTheme.COL_FG2;
            case FAILED -> ImGuiTheme.COL_DANGER;
        };
        String lead = switch (save.state()) {
            case SAVED -> Icons.CHECK + " ";
            case SAVING -> "";
            case FAILED -> Icons.WARNING + " ";
        };
        ui.textCentredY(draw, caption, x, y, h, col, lead);
        float cx = x + ui.width(caption, lead);
        String file = DOT + view.configFile();
        boolean hasRoomForFile = cx + ui.width(mono, save.text() + file) <= right;
        String text = hasRoomForFile ? save.text() : ui.ellipsize(mono, save.text(), Math.max(0f, right - cx));
        ui.textCentredY(draw, mono, cx, y, h, col, text);
        if (!text.equals(save.text()) && ImGui.isMouseHoveringRect(cx, y, cx + ui.width(mono, text), y + h)) {
            ImGui.setTooltip(save.text());
        }
        cx += ui.width(mono, text);
        if (hasRoomForFile) {
            ui.textCentredY(draw, mono, cx, y, h, ImGuiTheme.COL_FG2, file);
            cx += ui.width(mono, file);
        }
        return cx;
    }

    private void note(ImDrawList draw, ActionNote note, float x, float y, float h, float right) {
        ImFont font = ui.fonts().caption();
        if (right - x <= 0f) {
            return;
        }
        int col = note.isError() ? ImGuiTheme.COL_DANGER : ImGuiTheme.COL_FG2;
        ui.textCentredY(draw, font, x, y, h, col, ui.ellipsize(font, note.text(), right - x));
    }

    // ── Section list ───────────────────────────────────────────────────────

    private void toc(List<SectionView> all, List<SectionView> shown, float height) {
        ImGuiTheme.Metrics m = ui.m();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, m.u(3), m.u(4));
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0f, TOC_GAP_PX);
        ImGui.beginChild("##settings-toc", m.fontSize() * TOC_WIDTH_EM, height, false,
                ImGuiWindowFlags.AlwaysUseWindowPadding);
        float right = ImGui.getWindowPosX() + ImGui.getWindowWidth() - m.hairline() * 0.5f;
        float top = ImGui.getWindowPosY();
        ImGui.getWindowDrawList().addLine(right, top, right, top + ImGui.getWindowHeight(),
                ImGuiTheme.COL_BORDER, m.hairline());
        for (SectionView section : all) {
            boolean isShown = shown.stream().anyMatch(s -> s.section() == section.section());
            if (tocItem(section, isShown) && isShown) {
                scrollTo = Optional.of(section.section());
            }
        }
        ImGui.endChild();
        ImGui.popStyleVar(2);
    }

    private boolean tocItem(SectionView section, boolean isShown) {
        ImGuiTheme.Metrics m = ui.m();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float w = ImGui.getContentRegionAvailX();
        float h = m.controlHeight();
        boolean clicked = ImGui.invisibleButton("##toc-" + section.section().name(), w, h);
        boolean isOn = section.section() == current && isShown;
        ImDrawList draw = ImGui.getWindowDrawList();
        if (isOn || (isShown && ImGui.isItemHovered())) {
            draw.addRectFilled(x, y, x + w, y + h, ImGuiTheme.COL_ELEVATED, m.radius());
        }
        float alpha = isShown ? 1f : DIM_ALPHA;
        int fg = Controls.scaleAlpha(isOn ? ImGuiTheme.COL_FG : ImGuiTheme.COL_FG2, alpha);
        ImFont small = ui.fonts().small();
        ui.textCentredY(draw, ui.fonts().caption(), x + m.u(2), y, h, fg, section.section().icon());
        ui.textCentredY(draw, small, x + m.u(2) + m.fontSize(), y, h, fg, section.section().shortTitle());
        section.count().ifPresent(n -> {
            String text = Integer.toString(n);
            ImFont mono = ui.fonts().monoCaption();
            ui.textCentredY(draw, mono, x + w - m.u(2) - ui.width(mono, text), y, h,
                    Controls.scaleAlpha(ImGuiTheme.COL_FG3, alpha), text);
        });
        return clicked;
    }

    // ── Content column ─────────────────────────────────────────────────────

    private void content(List<SectionView> shown, float height) {
        ImGuiTheme.Metrics m = ui.m();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, m.u(6), m.u(5));
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0f, 0f);
        ImGui.beginChild("##settings-content", 0f, height, false, ImGuiWindowFlags.AlwaysUseWindowPadding);
        applyScrollRequest();
        float width = Math.min(ImGui.getContentRegionAvailX(), m.fontSize() * COLUMN_MAX_EM);
        if (shown.isEmpty()) {
            nothingFound();
        }
        for (int i = 0; i < shown.size(); i++) {
            if (i > 0) {
                ImGui.dummy(0f, m.u(8));
            }
            section(shown.get(i), width);
        }
        ImGui.dummy(0f, m.fontSize() * BOTTOM_PAD_EM);
        followScroll(shown);
        ImGui.endChild();
        ImGui.popStyleVar(2);
    }

    private void section(SectionView view, float width) {
        ImGuiTheme.Metrics m = ui.m();
        SettingsSection section = view.section();
        sectionTops.put(section, ImGui.getCursorPosY());
        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImFont heading = ui.fonts().bodyMedium();
        float lineH = heading.getFontSize() * LINE_HEIGHT;
        float indent = m.fontSize() * SECTION_ICON_EM;
        ui.textCentredY(draw, ui.fonts().small(), x, y, lineH, ImGuiTheme.COL_FG2, section.icon());
        ui.textCentredY(draw, heading, x + indent, y, lineH, ImGuiTheme.COL_FG, section.title());
        float cy = y + lineH + m.u(1);
        ImFont small = ui.fonts().small();
        for (String line : ui.wrap(small, section.description(), width - indent)) {
            ui.text(draw, small, x + indent, cy, ImGuiTheme.COL_FG2, line);
            cy += small.getFontSize() * LINE_HEIGHT;
        }
        ImGui.setCursorScreenPos(x, cy + m.u(3));
        rows.render(view.items(), width, model);
    }

    private void nothingFound() {
        ImFont font = ui.fonts().small();
        ImDrawList draw = ImGui.getWindowDrawList();
        ui.text(draw, font, ImGui.getCursorScreenPosX(), ImGui.getCursorScreenPosY(), ImGuiTheme.COL_FG2,
                "No setting matches \"" + query.get().strip() + "\".");
        ImGui.dummy(0f, font.getFontSize() * LINE_HEIGHT);
    }

    private void applyScrollRequest() {
        scrollTo.ifPresent(section -> {
            Float top = sectionTops.get(section);
            if (top != null) {
                ImGui.setScrollY(Math.max(0f, top - ui.m().u(3)));
                current = section;
            }
        });
        scrollTo = Optional.empty();
        if (scrollDownBy != 0f) {
            ImGui.setScrollY(ImGui.getScrollY() + scrollDownBy);
            scrollDownBy = 0f;
        }
    }

    /** Highlights, in the section list, the last section whose heading has scrolled past the top. */
    private void followScroll(List<SectionView> shown) {
        float line = ImGui.getScrollY() + ui.m().fontSize() * SPY_OFFSET_EM;
        for (SectionView view : shown) {
            Float top = sectionTops.get(view.section());
            if (top != null && top <= line) {
                current = view.section();
            }
        }
        // Within a line of the end: the last sections may never reach the top, and the end moves as rows settle.
        float slack = ui.m().fontSize();
        boolean isAtBottom = ImGui.getScrollMaxY() > 0f && ImGui.getScrollY() >= ImGui.getScrollMaxY() - slack;
        if (isAtBottom && !shown.isEmpty()) {
            current = shown.getLast().section();
        }
        if (!shown.isEmpty() && shown.stream().noneMatch(s -> s.section() == current)) {
            current = shown.getFirst().section();
        }
    }
}
